#include "mtmd-helper.h"
#include "llama_jni.h"
#include <memory>
#include <stdexcept>
#include <vector>

namespace {
template<typename Type, auto release>
struct Deleter { void operator()(Type *value) const { release(value); } };

template<typename Type, auto release>
using Owner = std::unique_ptr<Type, Deleter<Type, release>>;

struct AudioModel {
    Owner<llama_model, llama_model_free> model;
    Owner<llama_context, llama_free> decoder;
    Owner<mtmd_context, mtmd_free> encoder;
};

void require(bool condition, const char *message) {
    if (!condition) throw std::runtime_error(message);
}

std::string transcribe(AudioModel &audio, const std::vector<float> &samples, const std::string &language) {
    auto decoder = audio.decoder.get();
    auto encoder = audio.encoder.get();
    auto vocab = llama_model_get_vocab(audio.model.get());
    llama_memory_clear(llama_get_memory(decoder), true);
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
    require(mtmd_helper_eval_chunks(encoder, decoder, chunks.get(), 0, 0, llama_n_batch(decoder), true, &position) == 0, "音声入力の評価に失敗しました");
    Owner<llama_sampler, llama_sampler_free> sampler(llama_sampler_init_greedy());
    std::string result;
    while (position < static_cast<llama_pos>(llama_n_ctx(decoder))) {
        auto token = llama_sampler_sample(sampler.get(), decoder, -1);
        if (llama_vocab_is_eog(vocab, token)) return result;
        char local[256];
        int size = llama_token_to_piece(vocab, token, local, sizeof(local), 0, true);
        if (size >= 0) result.append(local, size);
        else {
            std::string piece(-size, '\0');
            require(llama_token_to_piece(vocab, token, piece.data(), -size, 0, true) == -size, "トークンを復号できません");
            result += piece;
        }
        require(llama_decode(decoder, llama_batch_get_one(&token, 1)) == 0, "書き起こしの生成に失敗しました");
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
        auto params = mtmd_context_params_default();
        params.use_gpu = gpu;
        params.device = devices[0];
        params.n_threads = encoder_threads;
        audio->encoder.reset(mtmd_init_from_file(to_std_string(env, projector_path).c_str(), audio->model.get(), params));
        require(audio->encoder && mtmd_get_audio_sample_rate(audio->encoder.get()) == 16000, "16 kHzの音声エンコーダーを読み込めません");
        return reinterpret_cast<jlong>(audio.release());
    } catch (const std::exception &error) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
        return 0;
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_app_plamotranslate_llm_LlamaNative_nativeTranscribe(
    JNIEnv *env, jobject, jlong handle, jfloatArray pcm, jstring language) {
    try {
        std::vector<float> samples(env->GetArrayLength(pcm));
        env->GetFloatArrayRegion(pcm, 0, samples.size(), samples.data());
        if (env->ExceptionCheck()) return nullptr;
        auto result = transcribe(*reinterpret_cast<AudioModel *>(handle), samples, to_std_string(env, language));
        auto bytes = env->NewByteArray(result.size());
        if (bytes) env->SetByteArrayRegion(bytes, 0, result.size(), reinterpret_cast<const jbyte *>(result.data()));
        return bytes;
    } catch (const std::exception &error) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL Java_app_plamotranslate_llm_LlamaNative_nativeFreeAudio(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<AudioModel *>(handle);
}
