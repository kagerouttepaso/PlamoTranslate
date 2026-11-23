#include "mtmd-helper.h"
#include "llama_jni.h"
#include <atomic>
#include <memory>
#include <new>
#include <stdexcept>
#include <type_traits>
#include <vector>

namespace {
template<typename Type, auto release>
struct Deleter { void operator()(Type *value) const { release(value); } };

template<typename Type, auto release>
using Owner = std::unique_ptr<Type, Deleter<Type, release>>;

struct Cancelled {};

struct AudioRequest {
    const int64_t id;
    std::atomic<bool> cancelled{false};
    unsigned encoder_nodes = 0;
    bool entered_decoder = false;

    void check() const { if (cancelled.load()) throw Cancelled{}; }
};

static_assert(std::is_trivially_destructible_v<AudioRequest> && std::atomic<bool>::is_always_lock_free);

AudioRequest *request_from(JNIEnv *env, jobject token) {
    return static_cast<AudioRequest *>(env->GetDirectBufferAddress(token));
}

struct AudioModel {
    AudioRequest *active = nullptr;
    Owner<llama_model, llama_model_free> model;
    Owner<llama_context, llama_free> decoder;
    Owner<mtmd_context, mtmd_free> encoder;
};

struct Evaluation {
    AudioModel &audio;
    Evaluation(AudioModel &audio, AudioRequest &request) : audio(audio) { audio.active = &request; }
    ~Evaluation() {
        llama_synchronize(audio.decoder.get());
        llama_memory_clear(llama_get_memory(audio.decoder.get()), true);
        audio.active = nullptr;
    }
};

bool encoder_checkpoint(ggml_tensor *, bool ask, void *data) {
    auto *request = static_cast<AudioModel *>(data)->active;
    if (!request) return !ask;
    if (ask) return ++request->encoder_nodes % 8 == 0 || request->cancelled.load();
    if (request->encoder_nodes == 8) log_audio_stage(request->id, "音声エンコーダー");
    return !request->cancelled.load();
}

int32_t batch_checkpoint(const mtmd_helper_embd_batch *, void *data) {
    auto &request = *static_cast<AudioRequest *>(data);
    if (!request.entered_decoder) {
        request.entered_decoder = true;
        log_audio_stage(request.id, "音声入力");
    }
    return request.cancelled.load() ? 1 : 0;
}

void require(bool condition, const char *message) {
    if (!condition) throw std::runtime_error(message);
}

std::string transcribe(AudioModel &audio, AudioRequest &request, const std::vector<float> &samples, const std::string &language) {
    request.check();
    Evaluation evaluation(audio, request);
    auto decoder = audio.decoder.get();
    auto encoder = audio.encoder.get();
    auto vocab = llama_model_get_vocab(audio.model.get());
    std::string prompt = std::string("<|im_start|>system\n<|im_end|>\n<|im_start|>user\n") + mtmd_default_marker()
        + "<|im_end|>\n<|im_start|>assistant\n";
    if (!language.empty()) prompt += "language " + language + "<asr_text>";
    mtmd_input_text input{prompt.data(), prompt.size(), false, true};
    Owner<mtmd_bitmap, mtmd_bitmap_free> bitmap(mtmd_bitmap_init_from_audio(samples.size(), samples.data()));
    Owner<mtmd_input_chunks, mtmd_input_chunks_free> chunks(mtmd_input_chunks_init());
    const mtmd_bitmap *bitmaps[] = {bitmap.get()};
    require(bitmap && chunks && mtmd_tokenize(encoder, chunks.get(), &input, bitmaps, 1) == 0, "音声をトークンに変換できません");
    require(mtmd_helper_get_n_tokens(chunks.get()) < llama_n_ctx(decoder), "音声がコンテキストに収まりません");
    llama_pos position = 0;
    constexpr int batch_size = 32;
    const auto count = mtmd_input_chunks_size(chunks.get());
    for (size_t index = 0; index < count; ++index) {
        request.check();
        auto chunk = mtmd_input_chunks_get(chunks.get(), index);
        int status;
        if (mtmd_input_chunk_get_type(chunk) == MTMD_INPUT_CHUNK_TYPE_AUDIO) {
            status = mtmd_encode_chunk(encoder, chunk);
            request.check();
            require(status == 0, "音声エンコーダーの評価に失敗しました");
            status = mtmd_helper_decode_image_chunk(encoder, decoder, chunk, mtmd_get_output_embd(encoder),
                position, 0, batch_size, &position, batch_checkpoint, &request);
        } else {
            status = mtmd_helper_eval_chunk_single(encoder, decoder, chunk, position, 0, batch_size, index + 1 == count, &position);
        }
        request.check();
        require(status == 0, "音声入力の評価に失敗しました");
    }
    Owner<llama_sampler, llama_sampler_free> sampler(llama_sampler_init_greedy());
    std::string result;
    while (position < static_cast<llama_pos>(llama_n_ctx(decoder))) {
        request.check();
        auto token = llama_sampler_sample(sampler.get(), decoder, -1);
        if (llama_vocab_is_eog(vocab, token)) return result;
        const bool first_token = result.empty();
        char local[256];
        int size = llama_token_to_piece(vocab, token, local, sizeof(local), 0, true);
        if (size >= 0) result.append(local, size);
        else {
            std::string piece(-size, '\0');
            require(llama_token_to_piece(vocab, token, piece.data(), -size, 0, true) == -size, "トークンを復号できません");
            result += piece;
        }
        request.check();
        const int status = llama_decode(decoder, llama_batch_get_one(&token, 1));
        if (first_token) log_audio_stage(request.id, "文字生成");
        request.check();
        require(status == 0, "書き起こしの生成に失敗しました");
        ++position;
    }
    throw std::runtime_error("書き起こしがコンテキストに収まりません");
}
}

extern "C" JNIEXPORT jlong JNICALL Java_app_plamotranslate_llm_LlamaNative_nativeLoadAudio(
    JNIEnv *env, jobject, jstring model_path, jstring projector_path, jboolean gpu, jint encoder_threads, jint batch_threads, jint generation_threads) {
    try {
        auto audio = std::make_unique<AudioModel>();
        auto model_params = llama_model_default_params();
        ggml_backend_dev_t devices[] = {gpu ? gpu_device() : nullptr, nullptr};
        require(!gpu || devices[0], "GPUが登録されていません");
        model_params.devices = devices;
        model_params.n_gpu_layers = gpu ? -1 : 0;
        audio->model.reset(llama_model_load_from_file(to_std_string(env, model_path).c_str(), model_params));
        require(bool(audio->model), "音声モデルを読み込めません");
        auto context_params = llama_context_default_params();
        context_params.n_ctx = 2048;
        context_params.n_threads = generation_threads;
        context_params.n_threads_batch = batch_threads;
        context_params.offload_kqv = context_params.op_offload = gpu;
        audio->decoder.reset(llama_init_from_model(audio->model.get(), context_params));
        require(bool(audio->decoder), "音声デコーダーを初期化できません");
        if (!gpu) llama_set_abort_callback(audio->decoder.get(), [](void *data) {
            auto *active = static_cast<AudioModel *>(data)->active;
            return active && active->cancelled.load();
        }, audio.get());
        auto params = mtmd_context_params_default();
        params.use_gpu = gpu;
        params.device = devices[0];
        params.n_threads = encoder_threads;
        params.cb_eval = encoder_checkpoint;
        params.cb_eval_user_data = audio.get();
        audio->encoder.reset(mtmd_init_from_file(to_std_string(env, projector_path).c_str(), audio->model.get(), params));
        require(audio->encoder && mtmd_get_audio_sample_rate(audio->encoder.get()) == 16000, "16 kHzの音声エンコーダーを読み込めません");
        return reinterpret_cast<jlong>(audio.release());
    } catch (const std::exception &error) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
        return 0;
    }
}

extern "C" JNIEXPORT jobject JNICALL Java_app_plamotranslate_llm_LlamaNative_nativeNewAudioRequest(JNIEnv *env, jobject) {
    static std::atomic<int64_t> next_id{1};
    auto buffer = env->FindClass("java/nio/ByteBuffer");
    if (!buffer) return nullptr;
    auto allocate = env->GetStaticMethodID(buffer, "allocateDirect", "(I)Ljava/nio/ByteBuffer;");
    if (!allocate) return nullptr;
    auto token = env->CallStaticObjectMethod(buffer, allocate, static_cast<jint>(sizeof(AudioRequest)));
    if (!token || env->ExceptionCheck()) return nullptr;
    new (env->GetDirectBufferAddress(token)) AudioRequest{next_id.fetch_add(1)};
    return token;
}

extern "C" JNIEXPORT void JNICALL Java_app_plamotranslate_llm_LlamaNative_nativeCancel(JNIEnv *env, jobject, jobject token) {
    request_from(env, token)->cancelled.store(true);
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_app_plamotranslate_llm_LlamaNative_nativeTranscribe(
    JNIEnv *env, jobject, jlong handle, jobject token, jfloatArray pcm, jstring language) {
    try {
        std::vector<float> samples(env->GetArrayLength(pcm));
        env->GetFloatArrayRegion(pcm, 0, samples.size(), samples.data());
        if (env->ExceptionCheck()) return nullptr;
        auto &request = *request_from(env, token);
        auto result = transcribe(*reinterpret_cast<AudioModel *>(handle), request, samples, to_std_string(env, language));
        request.check();
        auto bytes = env->NewByteArray(result.size());
        if (bytes) env->SetByteArrayRegion(bytes, 0, result.size(), reinterpret_cast<const jbyte *>(result.data()));
        return bytes;
    } catch (const Cancelled &) {
        log_audio_stage(request_from(env, token)->id, "取消完了");
        return nullptr;
    } catch (const std::exception &error) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL Java_app_plamotranslate_llm_LlamaNative_nativeFreeAudio(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<AudioModel *>(handle);
}
