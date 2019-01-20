#ifdef __ANDROID__
# include <android/api-level.h>
#endif

#ifdef __ANDROID_API__
# if __ANDROID_API__ == 15
  void Android15() {}
# endif
# if __ANDROID_API__ == 16
  void Android16() {}
# endif
# if __ANDROID_API__ == 17
  void Android17() {}
# endif
# if __ANDROID_API__ == 18
  void Android18() {}
# endif
# if __ANDROID_API__ == 19
  void Android19() {}
# endif
# if __ANDROID_API__ == 20
  void Android20() {}
# endif
# if __ANDROID_API__ == 21
  void Android21() {}
# endif
# if __ANDROID_API__ == 22
  void Android22() {}
# endif
# if __ANDROID_API__ == 23
  void Android23() {}
# endif
# if __ANDROID_API__ == 24
  void Android24() {}
# endif
#endif
