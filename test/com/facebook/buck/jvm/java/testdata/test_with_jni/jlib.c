#ifdef USE_JNI_H
#include <jni.h>
#else
#define JNIEXPORT
#define JNICALL
typedef int jint;
typedef void* JNIEnv;
typedef void* jclass;
#endif

#include <helper.h>

JNIEXPORT jint JNICALL Java_jlib_JLib_nativeGetPreValue(JNIEnv* env, jclass clazz) {
  return helper_function(47);
}
