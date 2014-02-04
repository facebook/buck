#include <jni.h>
#include <stdlib.h>

// Don't actually use this.
JNIEXPORT void JNICALL Java_fakenative_FakeSystem_exit(
    JNIEnv* env,
    jclass self,
    jint status) {
  exit(status);
}
