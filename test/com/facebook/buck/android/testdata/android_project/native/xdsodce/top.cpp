#include "top.h"

#include "mid.h"
#include "bot.h"

int unused(int) {
  return 1;
}
int JNI_OnLoad(int a, int b) {
  return midFromTop(a) + botFromTop(b);
}