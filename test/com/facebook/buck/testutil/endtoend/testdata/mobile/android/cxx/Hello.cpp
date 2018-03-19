/*
 * Copyright 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

#include <jni.h>
#include <type_traits>
#include "common/hello.h"

static jstring getHelloString(JNIEnv *env, jobject obj)
{
  const char *str = helloString();
  return env->NewStringUTF(str);
}

extern "C"
JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
  JNIEnv* env(nullptr);
  if (JNI_OK != vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6)) {
    return JNI_ERR;
  }

  jclass helloClass = env->FindClass("com/facebook/buck/demo/Hello");
  JNINativeMethod methods[] = {
    { "getHelloString", "()Ljava/lang/String;", (void*) getHelloString },
  };

  if (JNI_OK != env->RegisterNatives(helloClass, methods, std::extent<decltype(methods)>::value)) {
    return JNI_ERR;
  }
  return JNI_VERSION_1_6;
}
