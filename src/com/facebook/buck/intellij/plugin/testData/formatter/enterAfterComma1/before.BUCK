foo_android_library(
  name = 'locale',<caret>
  srcs = glob(['*.java'], excludes = SOME_REFERENCE),
  deps = [
    '//java/com/foo/common/android:android',
    '//java/com/foo/debug/log:log',
    '//third-party/java/abc:def',
  ],
  exported_deps = [
    ':which',
  ],
  visibility = [
    'PUBLIC',
  ],
)
