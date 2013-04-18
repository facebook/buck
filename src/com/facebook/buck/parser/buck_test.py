from buck import glob_pattern_to_regex_string
from buck import parse_git_ignore
import unittest
import re

class TestBuck(unittest.TestCase):

  def test_glob_pattern_to_regex_string_double_star(self):
    all_src = glob_pattern_to_regex_string('src/**/*.java')
    self.assertEqual('^src/(.*)\\.java$', all_src)

    all_src_re = re.compile(all_src)
    self.assertTrue(all_src_re.match('src/Foo.java'))
    self.assertFalse(all_src_re.match('src/Foodjava'))
    self.assertTrue(all_src_re.match('src/com/facebook/Foo.java'))
    self.assertFalse(all_src_re.match('src/com/facebook/Foodjava'))


  def test_glob_pattern_to_regex_string_single_star(self):
    client_src = glob_pattern_to_regex_string('src/com/facebook/bookmark/client/*.java')
    self.assertEqual('^src/com/facebook/bookmark/client/[^/]*\\.java$', client_src)

    client_src_re = re.compile(client_src)
    self.assertTrue(client_src_re.match('src/com/facebook/bookmark/client/BookmarkClient.java'))
    self.assertFalse(client_src_re.match('src/com/facebook/bookmark/client/util/Util.java'))


  def test_glob_pattern_to_regex_string_single_star_no_directory_prefix(self):
    star_dot_java = glob_pattern_to_regex_string('*.java')
    self.assertEqual('^[^/]*\\.java$', star_dot_java)

    star_dot_java_re = re.compile(star_dot_java)
    self.assertTrue(star_dot_java_re.match('Main.java'))
    self.assertFalse(star_dot_java_re.match('com/example/Main.java'))


  def test_glob_pattern_to_regex_string_double_star_no_subdir(self):
    all_java_tests = glob_pattern_to_regex_string('**/*Test.java')
    self.assertEqual('^(.*)Test\\.java$', all_java_tests)

    all_java_tests_re = re.compile(all_java_tests)
    self.assertFalse(all_java_tests_re.match('Main.java'))
    self.assertTrue(all_java_tests_re.match('MainTest.java'))
    self.assertFalse(all_java_tests_re.match('com/example/Main.java'))
    self.assertTrue(all_java_tests_re.match('com/example/MainTest.java'))

  def test_git_ignore_parser(self):
    # Test empty file
    self.assertEqual([], parse_git_ignore([]))

    # Test that deep paths are not parsed.
    self.assertEqual([], parse_git_ignore([
        'a/b/c/d/\n',
        '/a/b/c/d/\n',
        ]))

    # Test that top level directories are parsed and comments are ignored.
    self.assertEqual(['a', 'b'], parse_git_ignore([
          '/a/\n',
          '/b/\n',
          '#/c/\n',
          ]))

    # Test that other patterns are not parsed.
    self.assertEqual([], parse_git_ignore([
          '/a/*\n',
          '*.out\n',
          'b/*.txt\n',
          ]))

    # Test that files that do not end in a new line are parsed.
    self.assertEqual(['b'], parse_git_ignore([
          '/a/*\n',
          '/b/',
          ]))

if __name__ == '__main__':
  unittest.main()
