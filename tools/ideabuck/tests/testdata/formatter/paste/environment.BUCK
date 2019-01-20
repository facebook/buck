SOME_REFERENCE = [
  'Ref.java',
]

foo_android_library(
  name = 'locale',
  srcs = glob(['*.java'], excludes = SOME_REFERENCE),
  deps = [
    '//java/com/foo/common/android:android',<caret>
  ],
  exported_deps = [
    ':which',
  ],
  visibility = [
    'PUBLIC',
  ],
)
