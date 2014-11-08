#include <string.h>
#include <jni.h>

jstring
Java_exotest_LogActivity_stringOneFromJNI(JNIEnv* env, jobject thiz) {
  return (*env)->NewStringUTF(env, "one_1a");
}
