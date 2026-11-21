#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <audioclient.h>
#include <audioclientactivationparams.h>
#include <mmdeviceapi.h>
#include <wrl.h>
#include <jni.h>

#include <cstdint>
#include <cstdio>
#include <memory>
#include <new>

namespace {
using Microsoft::WRL::ComPtr;
using Microsoft::WRL::FtmBase;
using Microsoft::WRL::Make;
using Microsoft::WRL::RuntimeClass;
using Microsoft::WRL::RuntimeClassFlags;
using Microsoft::WRL::ClassicCom;

constexpr DWORD sample_rate = 16000;
constexpr DWORD read_timeout_ms = 100;

struct AudioError {
    const char * operation;
    HRESULT result;
};

void check(HRESULT result, const char * operation) {
    if (FAILED(result)) throw AudioError{operation, result};
}

void report(JNIEnv * env, const AudioError & error) {
    char message[192];
    std::snprintf(message, sizeof(message), "%s (0x%08lX)", error.operation, static_cast<unsigned long>(error.result));
    if (jclass type = env->FindClass("java/io/IOException")) env->ThrowNew(type, message);
}

class EventHandle {
    HANDLE handle_ = CreateEventW(nullptr, FALSE, FALSE, nullptr);
public:
    EventHandle() {
        if (!handle_) check(HRESULT_FROM_WIN32(GetLastError()), "音声イベントを作れません");
    }
    ~EventHandle() { CloseHandle(handle_); }
    EventHandle(const EventHandle &) = delete;
    EventHandle & operator=(const EventHandle &) = delete;
    HANDLE get() const { return handle_; }
};

class ComApartment {
public:
    ComApartment() { check(CoInitializeEx(nullptr, COINIT_MULTITHREADED), "音声用の COM を初期化できません"); }
    ~ComApartment() { CoUninitialize(); }
};

// 仮想デバイスの作成結果は MTA に届く。タイムアウト後も OS がコールバックを解放するまでイベントを持つ。
class Activation final : public RuntimeClass<
    RuntimeClassFlags<ClassicCom>, IActivateAudioInterfaceCompletionHandler, FtmBase> {
public:
    EventHandle completed;
    ComPtr<IAudioClient> client;
    HRESULT result = E_UNEXPECTED;

    HRESULT STDMETHODCALLTYPE ActivateCompleted(IActivateAudioInterfaceAsyncOperation * operation) override {
        ComPtr<IUnknown> activated;
        HRESULT activation_result = E_UNEXPECTED;
        result = operation->GetActivateResult(&activation_result, activated.GetAddressOf());
        if (SUCCEEDED(result)) result = activation_result;
        if (SUCCEEDED(result)) result = activated.As(&client);
        SetEvent(completed.get());
        return S_OK;
    }
};

ComPtr<IAudioClient> open_loopback() {
    AUDIOCLIENT_ACTIVATION_PARAMS parameters{};
    parameters.ActivationType = AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK;
    parameters.ProcessLoopbackParams.TargetProcessId = GetCurrentProcessId();
    parameters.ProcessLoopbackParams.ProcessLoopbackMode = PROCESS_LOOPBACK_MODE_EXCLUDE_TARGET_PROCESS_TREE;
    PROPVARIANT variant{};
    variant.vt = VT_BLOB;
    variant.blob = {sizeof(parameters), reinterpret_cast<BYTE *>(&parameters)};
    auto handler = Make<Activation>();
    if (!handler) check(E_OUTOFMEMORY, "再生音の取り込みを準備できません");
    ComPtr<IActivateAudioInterfaceAsyncOperation> operation;
    check(ActivateAudioInterfaceAsync(VIRTUAL_AUDIO_DEVICE_PROCESS_LOOPBACK, __uuidof(IAudioClient), &variant,
        handler.Get(), operation.GetAddressOf()), "再生音の取り込みを準備できません");
    const auto wait = WaitForSingleObject(handler->completed.get(), 5000);
    if (wait != WAIT_OBJECT_0) {
        check(HRESULT_FROM_WIN32(wait == WAIT_TIMEOUT ? ERROR_TIMEOUT : GetLastError()), "再生音の取り込みの準備が終わりません");
    }
    check(handler->result, "再生音の取り込みを準備できません");
    return handler->client;
}

ComPtr<IAudioClient> open_microphone() {
    ComPtr<IMMDeviceEnumerator> devices;
    check(CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL, IID_PPV_ARGS(devices.GetAddressOf())),
        "音声デバイスの一覧を開けません");
    ComPtr<IMMDevice> microphone;
    check(devices->GetDefaultAudioEndpoint(eCapture, eConsole, microphone.GetAddressOf()), "既定のマイクを開けません");
    ComPtr<IAudioClient> client;
    check(microphone->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr, &client), "マイクを起動できません");
    return client;
}

class Capture {
    ComApartment apartment_;
    EventHandle ready_;
    ComPtr<IAudioClient> client_;
    ComPtr<IAudioCaptureClient> capture_;
    bool loopback_;

public:
    explicit Capture(bool loopback) : client_(loopback ? open_loopback() : open_microphone()), loopback_(loopback) {
        // 共有モードの Windows オーディオエンジンで、モノラル化と 16 kHz への変換を行う。
        WAVEFORMATEX format{};
        format.wFormatTag = WAVE_FORMAT_PCM;
        format.nChannels = 1;
        format.nSamplesPerSec = sample_rate;
        format.wBitsPerSample = 16;
        format.nBlockAlign = sizeof(std::int16_t);
        format.nAvgBytesPerSec = sample_rate * format.nBlockAlign;
        DWORD flags = AUDCLNT_STREAMFLAGS_EVENTCALLBACK | AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM | AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY;
        if (loopback_) flags |= AUDCLNT_STREAMFLAGS_LOOPBACK;
        check(client_->Initialize(AUDCLNT_SHAREMODE_SHARED, flags, 0, 0, &format, nullptr), "音声入力の形式を設定できません");
        check(client_->SetEventHandle(ready_.get()), "音声イベントを登録できません");
        check(client_->GetService(IID_PPV_ARGS(capture_.GetAddressOf())), "音声入力を開けません");
        check(client_->Start(), "音声入力を開始できません");
    }

    ~Capture() { client_->Stop(); }

    jfloatArray read(JNIEnv * env) {
        UINT32 frames = 0;
        check(capture_->GetNextPacketSize(&frames), "音声入力を読み取れません");
        if (frames == 0) {
            const auto wait = WaitForSingleObject(ready_.get(), read_timeout_ms);
            if (wait == WAIT_FAILED) check(HRESULT_FROM_WIN32(GetLastError()), "音声イベントを待てません");
            check(capture_->GetNextPacketSize(&frames), "音声入力を読み取れません");
            if (frames == 0) {
                // 再生側がストリームを閉じても、残った発話を VAD が確定できるよう無音を進める。
                if (!loopback_ || wait != WAIT_TIMEOUT) return nullptr;
                return env->NewFloatArray(sample_rate * read_timeout_ms / 1000);
            }
        }
        auto output = env->NewFloatArray(static_cast<jsize>(frames));
        if (!output) return nullptr;
        BYTE * data = nullptr;
        DWORD flags = 0;
        check(capture_->GetBuffer(&data, &frames, &flags, nullptr, nullptr), "音声のサンプルを受け取れません");
        if (!(flags & AUDCLNT_BUFFERFLAGS_SILENT)) {
            if (jfloat * samples = env->GetFloatArrayElements(output, nullptr)) {
                const auto * pcm = reinterpret_cast<const std::int16_t *>(data);
                for (UINT32 i = 0; i < frames; ++i) samples[i] = pcm[i] / 32768.0f;
                env->ReleaseFloatArrayElements(output, samples, 0);
            }
        }
        check(capture_->ReleaseBuffer(frames), "音声のサンプルを返せません");
        return output;
    }
};
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_plamotranslate_desktop_WindowsAudio_open(JNIEnv * env, jobject, jboolean loopback) {
    try {
        return reinterpret_cast<jlong>(new Capture(loopback));
    } catch (const AudioError & error) {
        report(env, error);
    } catch (const std::bad_alloc &) {
        report(env, {"音声入力を準備するメモリが足りません", E_OUTOFMEMORY});
    }
    return 0;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_app_plamotranslate_desktop_WindowsAudio_read(JNIEnv * env, jobject, jlong handle) {
    try {
        return reinterpret_cast<Capture *>(handle)->read(env);
    } catch (const AudioError & error) {
        report(env, error);
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_app_plamotranslate_desktop_WindowsAudio_close(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<Capture *>(handle);
}
