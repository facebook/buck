SOME_REFERENCE = [
  'Ref.java',
]

foo_android_library(
  name = 'locale',
  srcs = glob(['*.java'], excludes = SOME_REFERENCE),
  deps = [
    '//java/com/foo/common/android:android',
    '//java/com/foo/debug/log:log',
    '//third-party/java/abc:def',
  ],
  exported_deps = [
    ':which',
  ]
,
  visibility = [
    'PUBLIC',
  ],
)
