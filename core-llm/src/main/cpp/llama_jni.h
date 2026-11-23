#pragma once

#include <jni.h>
#include <string>
#include "ggml-backend.h"

ggml_backend_dev_t gpu_device();
std::string to_std_string(JNIEnv *env, jstring value);
