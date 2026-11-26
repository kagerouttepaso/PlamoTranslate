#include <cerrno>
#include <jni.h>
#include "cpu_affinity.h"

extern "C" JNIEXPORT jint JNICALL
Java_app_plamotranslate_featurelab_CpuLab_reset(JNIEnv *, jobject) {
    return allow_all_cpus() == 0 ? 0 : errno;
}
