from buck import LazyBuildEnvPartial
from buck import split_path
from buck import glob_walk_internal
from buck import glob_walk
from buck import glob_match
from buck import relpath
from buck import path_join
from buck import symlink_aware_walk
from buck import glob_module
import fnmatch
import unittest
import re
import os
import posixpath


class TestBuck(unittest.TestCase):

    def test_split_path(self):
        self.assertEqual(
            ['', 'foo', 'bar', 'baz.java'],
            split_path('/foo/bar/baz.java'))
        self.assertEqual(
            ['foo', 'bar', 'baz.java'],
            split_path('foo/bar/baz.java'))
        self.assertEqual(['', 'foo', 'bar', ''], split_path('/foo/bar/'))

    def glob_match_using_glob_walk(self, pattern_to_test, path_to_test,
                                   include_dotfiles=False):
        chunks = split_path(path_to_test)
        # Simulate a "file system" with only one path, that is path_to_test
        # Note: for the purpose of simulating glob_match, we do not treat empty
        # names as special.

        def iglob(pattern):
            tokens = split_path(pattern)
            n = len(tokens)
            self.assertTrue(n > 0)
            if n > len(chunks):
                return
            self.assertEqual(chunks[:n - 1], tokens[:n - 1])
            token = tokens[n - 1]
            chunk = chunks[n - 1]
            if (not include_dotfiles and (not token or token[0] != '.') and
                    chunk and chunk[0] == '.'):
                return
            if fnmatch.fnmatch(chunk, token):
                yield os.path.sep.join(chunks[:n])

        def isresult(path):
            if path is None:
                return False
            return path_to_test == path

        visited = set()
        tokens = split_path(pattern_to_test)
        return next(glob_walk_internal(
            path_join,
            iglob,
            isresult,
            visited,
            tokens,
            None,
            None), None) is not None

    def run_test_glob_match_both_ways(self, result, pattern, path,
                                      include_dotfiles=False):
        self.assertEqual(
            result,
            glob_match(pattern, path, include_dotfiles=include_dotfiles),
            "glob_match('%s', '%s', include_dotfiles=%s) should be %s" % (
                pattern, path, include_dotfiles, result))
        self.assertEqual(
            result,
            self.glob_match_using_glob_walk(
                pattern, path, include_dotfiles=include_dotfiles),
            "glob_match_using_glob_walk('%s', '%s', include_dotfiles=%s) "
            "should be %s" % (pattern, path, include_dotfiles, result))

    def test_glob_match_simple(self):
        patterns = ['', '/', 'src', '/src', 'foo/bar', 'foo//bar', 'foo/bar/']
        for pattern in patterns:
            for path in patterns:
                self.run_test_glob_match_both_ways(
                    pattern == path, pattern, path)

    def test_glob_match_simple_glob(self):
        pattern = '*'
        self.run_test_glob_match_both_ways(True, pattern, '')
        self.run_test_glob_match_both_ways(False, pattern, '/')
        self.run_test_glob_match_both_ways(True, pattern, 'src')
        self.run_test_glob_match_both_ways(False, pattern, '/src')

    def test_glob_match_simple_slash_glob(self):
        pattern = '/*'
        self.run_test_glob_match_both_ways(False, pattern, '')
        self.run_test_glob_match_both_ways(True, pattern, '/')
        self.run_test_glob_match_both_ways(False, pattern, 'src')
        self.run_test_glob_match_both_ways(True, pattern, '/src')

    def test_glob_match_simple_double_star(self):
        pattern = '**'
        self.run_test_glob_match_both_ways(True, pattern, '')
        self.run_test_glob_match_both_ways(True, pattern, '/')
        self.run_test_glob_match_both_ways(True, pattern, 'src')
        self.run_test_glob_match_both_ways(True, pattern, '/src')

    def test_glob_match_simple_slash_double_star(self):
        pattern = '/**'
        self.run_test_glob_match_both_ways(True, pattern, '')
        self.run_test_glob_match_both_ways(True, pattern, '/')
        self.run_test_glob_match_both_ways(False, pattern, 'src')
        self.run_test_glob_match_both_ways(True, pattern, '/src')

    def test_glob_match_double_star(self):
        pattern = 'src/**/*.java'
        self.run_test_glob_match_both_ways(True, pattern, 'src/Foo.java')
        self.run_test_glob_match_both_ways(False, pattern, '/src/Foo.java')
        self.run_test_glob_match_both_ways(False, pattern, 'src/Foodjava')
        self.run_test_glob_match_both_ways(
            True, pattern, 'src/com/facebook/Foo.java')
        self.run_test_glob_match_both_ways(
            False, pattern, 'src/com/facebook/Foodjava')

    def test_glob_match_single_star(self):
        client_src = 'src/com/facebook/bookmark/client/*.java'
        self.run_test_glob_match_both_ways(
            True,
            client_src,
            'src/com/facebook/bookmark/client/BookmarkClient.java')
        self.run_test_glob_match_both_ways(
            False,
            client_src,
            'src/com/facebook/bookmark/client/util/Util.java')

    def test_glob_match_single_star_no_directory_prefix(self):
        star_dot_java = '*.java'
        self.run_test_glob_match_both_ways(True, star_dot_java, 'Main.java')
        self.run_test_glob_match_both_ways(
            False, star_dot_java, 'com/example/Main.java')

    def test_glob_match_double_star_no_subdir(self):
        all_java_tests = '**/*Test.java'
        self.run_test_glob_match_both_ways(False, all_java_tests, 'Main.java')
        self.run_test_glob_match_both_ways(
            True, all_java_tests, 'MainTest.java')
        self.run_test_glob_match_both_ways(
            False, all_java_tests, 'com/example/Main.java')
        self.run_test_glob_match_both_ways(
            True, all_java_tests, 'com/example/MainTest.java')

    def test_glob_match_ignores_dot_files_and_dirs_by_default(self):
        all_java_tests = '**/*Test.java'
        self.run_test_glob_match_both_ways(
            True, all_java_tests, 'path/to/MyJavaTest.java')
        self.run_test_glob_match_both_ways(
            False, all_java_tests, 'path/to/.MyJavaTest.java')
        self.run_test_glob_match_both_ways(
            False, all_java_tests, 'path/.to/MyJavaTest.java')
        # The following case does not match any more.
        # For simplicity of the semantics, normalization should be done
        # outside the matching function.
        self.run_test_glob_match_both_ways(
            False, all_java_tests, './path/to/MyJavaTest.java')

    def test_glob_match_can_include_dot_files_and_dirs(self):
        all_java_tests = '**/*Test.java'
        self.run_test_glob_match_both_ways(
            True,
            all_java_tests,
            'path/to/MyJavaTest.java',
            include_dotfiles=True)
        self.run_test_glob_match_both_ways(
            True,
            all_java_tests,
            'path/to/.MyJavaTest.java',
            include_dotfiles=True)
        self.run_test_glob_match_both_ways(
            True,
            all_java_tests,
            'path/.to/MyJavaTest.java',
            include_dotfiles=True)
        self.run_test_glob_match_both_ways(
            True,
            all_java_tests,
            './path/to/MyJavaTest.java',
            include_dotfiles=True)

    def test_symlink_aware_glob_walk(self):
        real_iglob = glob_module.iglob
        real_islink = os.path.islink
        real_isfile = os.path.isfile
        real_realpath = os.path.realpath

        # a/
        #  b/
        #   c/
        #    file
        #    file2 -> file
        #    .file
        #   sibling -> c
        #   ancestor -> ../..
        all_paths = [
            'a',
            'a/b',
            'a/b/c',
            'a/b/c/file',
            'a/b/c/file2',
            'a/b/c/.file',
            'a/b/sibling',
            'a/b/ancestor',
        ]

        def mock_iglob(pattern):
            for path in all_paths:
                if glob_match(pattern, path):
                    yield path

        def mock_realpath(path):
            if path == 'a/b/sibling':
                return 'a/b/c'
            if path == 'a/b/ancestor':
                return 'a'
            if path == 'a/b/c/file2':
                return 'a/b/c/file'
            if path == 'a':
                return path
            self.fail('glob_walk should only call realpath on the root'
                      ' or on symlinks (was called on "%s").' % path)

        def mock_islink(path):
            return (path == 'a/b/sibling' or path == 'a/b/ancestor'
                    or path == 'a/b/c/file2')

        def mock_isfile(path):
            return (path == 'a/b/c/file' or path == 'a/b/c/file2'
                    or path == 'a/b/c/.file')

        try:
            glob_module.iglob = mock_iglob
            os.path.islink = mock_islink
            os.path.isfile = mock_isfile
            os.path.realpath = mock_realpath

            # Symlinked directories do not cause loop or duplicated results.
            # Symlinked files are allowed to duplicate results.
            # By default, dot files are ignored.
            result = [p for p in glob_walk('**', 'a')]
            self.assertEqual(['b/c/file', 'b/c/file2'], result)

            result = [p for p in glob_walk('**/*', 'a')]
            self.assertEqual(['b/c/file', 'b/c/file2'], result)

            result = [p for p in glob_walk('*/*/*', 'a')]
            self.assertEqual(['b/c/file', 'b/c/file2'], result)

        finally:
            glob_module.iglob = real_iglob
            os.path.islink = real_islink
            os.path.isfile = real_isfile
            os.path.realpath = real_realpath

    def test_lazy_build_env_partial(self):
        def cobol_binary(
                name,
                deps=[],
                build_env=None):
            return (name, deps, build_env)

        testLazy = LazyBuildEnvPartial(cobol_binary, {})
        self.assertEqual(
            ('HAL', [1, 2, 3], {}),
            testLazy.invoke(name='HAL', deps=[1, 2, 3]))
        testLazy.build_env = {'abc': 789}
        self.assertEqual(
            ('HAL', [1, 2, 3], {'abc': 789}),
            testLazy.invoke(name='HAL', deps=[1, 2, 3]))

    # Test the temporary reimplementation of relpath
    # TODO(user): upgrade to a jython including os.relpath
    def test_relpath(self):
        real_getcwd = os.getcwd
        try:
            os.getcwd = lambda: r"/home/user/bar"
            curdir = os.path.split(os.getcwd())[-1]
            self.assertRaises(ValueError, relpath, "")
            self.assertEqual("a", relpath("a"))
            self.assertEqual("a", relpath(posixpath.abspath("a")))
            self.assertEqual("a/b", relpath("a/b"))
            self.assertEqual("../a/b", relpath("../a/b"))
            self.assertEqual("../" + curdir + "/a", relpath("a", "../b"))
            self.assertEqual("../" + curdir + "/a/b", relpath("a/b", "../c"))
            self.assertEqual("../../a", relpath("a", "b/c"))
        finally:
            os.getcwd = real_getcwd


    def test_symlink_aware_walk(self):
        real_walk = os.walk
        real_realpath = os.path.realpath
        real_abspath = os.path.abspath

        # a/
        #  b/
        #   c/
        #    file
        #   sibling -> c
        #   ancestor -> ../..

        def mock_walk(base, **kwargs):
            self.assertEqual('a', base)

            dirs = ['b']
            yield ('a', dirs, [])
            self.assertEqual(['b'], dirs)

            dirs = ['c', 'sibling', 'ancestor']
            yield ('a/b', dirs, [])
            self.assertEqual(['c', 'sibling', 'ancestor'], dirs)

            yield ('a/b/c', [], ['file'])
            yield ('a/b/sibling', [], ['file'])

            dirs = ['b']
            yield ('a/b/ancestor', dirs, [])
            self.assertEqual([], dirs)

            raise StopIteration

        def mock_realpath(path):
            if path == 'a/b/sibling':
                return 'a/b/c'
            if path == 'a/b/ancestor':
                return 'a'
            return path

        def mock_abspath(path):
            return path

        try:
            os.walk = mock_walk
            os.path.realpath = mock_realpath
            os.path.abspath = mock_abspath
            result = set(root for (root, _, _) in symlink_aware_walk('a'))
            self.assertEqual(
                set([
                    'a',
                    'a/b',
                    'a/b/c',
                    'a/b/sibling',
                    ]),
                result)
        finally:
            os.walk = real_walk
            os.path.realpath = real_realpath
            os.path.abspath = real_abspath


if __name__ == '__main__':
    unittest.main()
