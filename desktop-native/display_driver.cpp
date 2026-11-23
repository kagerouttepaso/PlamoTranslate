#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <jni.h>
#include <string>

extern "C" JNIEXPORT jstring JNICALL Java_app_plamotranslate_desktop_DesktopQwen_nativeDisplayDrivers(JNIEnv *env, jobject) {
    std::wstring identity;
    const std::wstring prefix = L"\\Registry\\Machine\\";
    DISPLAY_DEVICEW device{};
    device.cb = sizeof(device);
    for (DWORD index = 0; EnumDisplayDevicesW(nullptr, index, &device, 0); ++index) {
        std::wstring key(device.DeviceKey);
        if (key.compare(0, prefix.size(), prefix) != 0) continue;
        wchar_t version[256]{};
        DWORD bytes = sizeof(version);
        if (RegGetValueW(HKEY_LOCAL_MACHINE, key.c_str() + prefix.size(), L"DriverVersion", RRF_RT_REG_SZ,
                         nullptr, version, &bytes) != ERROR_SUCCESS) continue;
        identity += std::wstring(device.DeviceID) + L":" + version + L";";
    }
    return env->NewString(reinterpret_cast<const jchar *>(identity.data()), static_cast<jsize>(identity.size()));
}
