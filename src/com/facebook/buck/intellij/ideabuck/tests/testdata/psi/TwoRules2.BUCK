# Rule for robolectric test
robolectric_test(
  # Comments
  contacts = ['foo@foo.com'],
  name = 'name',
  srcs = glob(['**/*Test.java']),
  deps = [
    '//java/com/foo/testing/robolectric/what:where',
    '//java/com/foo/ui/button:button',

  ],
  test_library_project_dir = '../../android_res/com/foo/ui/button/',
  source_under_test = [
    '//java/com/foo/ui/button:button',
  ],
)

project_config(
  test_target = ':button',
)
