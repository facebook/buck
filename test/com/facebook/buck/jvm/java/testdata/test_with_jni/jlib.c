#ifdef USE_JNI_H
#include <jni.h>
#else
#define JNIEXPORT
#define JNICALL
typedef int jint;
typedef void* JNIEnv;
typedef void* jclass;
#endif


JNIEXPORT jint JNICALL Java_jlib_JLib_nativeGetPreValue(JNIEnv* env, jclass clazz) {
  return 2;
}
