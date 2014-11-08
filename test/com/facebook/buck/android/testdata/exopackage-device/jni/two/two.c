#include <string.h>
#include <jni.h>

jstring
Java_exotest_LogActivity_stringTwoFromJNI(JNIEnv* env, jobject thiz) {
  return (*env)->NewStringUTF(env, "two_1a");
}
