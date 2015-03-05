from buck import glob_internal, LazyBuildEnvPartial
from pathlib import Path, PurePosixPath, PureWindowsPath
import os
import shutil
import tempfile
import unittest


class FakePathMixin(object):
    def glob(self, pattern):
        return self.glob_results.get(pattern)

    def is_file(self):
        return True


class FakePosixPath(FakePathMixin, PurePosixPath):
    pass


class FakeWindowsPath(FakePathMixin, PureWindowsPath):
    pass


def fake_path(fake_path_class, path, glob_results={}):
    # Path does magic in __new__ with its args; it's hard to add more without
    # changing that class. So we use a wrapper function to diddle with
    # FakePath's members.
    result = fake_path_class(path)
    result.glob_results = {}
    for pattern, paths in glob_results.iteritems():
        result.glob_results[pattern] = [result / fake_path_class(p) for p in paths]
    return result


class TestBuckPlatformBase(object):

    def test_glob_includes_simple(self):
        search_base = self.fake_path(
            'foo',
            glob_results={'*.java': ['A.java', 'B.java']})
        self.assertGlobMatches(
            ['A.java', 'B.java'],
            glob_internal(
                includes=['*.java'],
                excludes=[],
                include_dotfiles=False,
                allow_empty=False,
                search_base=search_base))

    def test_glob_includes_sort(self):
        search_base = self.fake_path(
            'foo',
            glob_results={'*.java': ['A.java', 'E.java', 'D.java', 'C.java', 'B.java']})
        self.assertGlobMatches(
            ['A.java', 'B.java', 'C.java', 'D.java', 'E.java'],
            glob_internal(
                includes=['*.java'],
                excludes=[],
                include_dotfiles=False,
                allow_empty=False,
                search_base=search_base))

    def test_glob_includes_multi(self):
        search_base = self.fake_path(
            'foo',
            glob_results={
                'bar/*.java': ['bar/A.java', 'bar/B.java'],
                'baz/*.java': ['baz/C.java', 'baz/D.java'],
            })
        self.assertGlobMatches(
            ['bar/A.java', 'bar/B.java', 'baz/C.java', 'baz/D.java'],
            glob_internal(
                includes=['bar/*.java', 'baz/*.java'],
                excludes=[],
                include_dotfiles=False,
                allow_empty=False,
                search_base=search_base))

    def test_glob_excludes_double_star(self):
        search_base = self.fake_path(
            'foo',
            glob_results={
                '**/*.java': ['A.java', 'B.java', 'Test.java'],
            })
        self.assertGlobMatches(
            ['A.java', 'B.java'],
            glob_internal(
                includes=['**/*.java'],
                excludes=['**/*Test.java'],
                include_dotfiles=False,
                allow_empty=False,
                search_base=search_base))

    def test_glob_excludes_multi(self):
        search_base = self.fake_path(
            'foo',
            glob_results={
                'bar/*.java': ['bar/A.java', 'bar/B.java'],
                'baz/*.java': ['baz/C.java', 'baz/D.java'],
            })
        self.assertGlobMatches(
            ['bar/B.java', 'baz/D.java'],
            glob_internal(
                includes=['bar/*.java', 'baz/*.java'],
                excludes=['*/[AC].java'],
                include_dotfiles=False,
                allow_empty=False,
                search_base=search_base))

    def test_glob_excludes_relative(self):
        search_base = self.fake_path(
            'foo',
            glob_results={
                '**/*.java': ['foo/A.java', 'foo/bar/B.java', 'bar/C.java'],
            })
        self.assertGlobMatches(
            ['foo/A.java', 'foo/bar/B.java'],
            glob_internal(
                includes=['**/*.java'],
                excludes=['bar/*.java'],
                include_dotfiles=False,
                allow_empty=False,
                search_base=search_base))

    def test_glob_includes_skips_dotfiles(self):
        search_base = self.fake_path(
            'foo',
            glob_results={'*.java': ['A.java', '.B.java']})
        self.assertGlobMatches(
            ['A.java'],
            glob_internal(
                includes=['*.java'],
                excludes=[],
                include_dotfiles=False,
                allow_empty=False,
                search_base=search_base))

    def test_glob_includes_does_not_skip_dotfiles_if_include_dotfiles(self):
        search_base = self.fake_path(
            'foo',
            glob_results={'*.java': ['A.java', '.B.java']})
        self.assertGlobMatches(
            ['.B.java', 'A.java'],
            glob_internal(
                includes=['*.java'],
                excludes=[],
                include_dotfiles=True,
                allow_empty=False,
                search_base=search_base))

    def test_lazy_build_env_partial(self):
        def cobol_binary(
                name,
                deps=[],
                build_env=None):
            return (name, deps, build_env)

        testLazy = LazyBuildEnvPartial(cobol_binary)
        testLazy.build_env = {}
        self.assertEqual(
            ('HAL', [1, 2, 3], {}),
            testLazy.invoke(name='HAL', deps=[1, 2, 3]))
        testLazy.build_env = {'abc': 789}
        self.assertEqual(
            ('HAL', [1, 2, 3], {'abc': 789}),
            testLazy.invoke(name='HAL', deps=[1, 2, 3]))

    def test_glob_errors_empty_results(self):
        search_base = self.fake_path(
            'foo',
            glob_results={'*.java': []})
        self.assertRaises(
            AssertionError,
            lambda:
            glob_internal(
                includes=['*.java'],
                excludes=[],
                include_dotfiles=False,
                allow_empty=False,
                search_base=search_base))

    def test_glob_allows_empty_results_with_flag(self):
        search_base = self.fake_path(
            'foo',
            glob_results={'*.java': []})
        self.assertGlobMatches(
            [],
            glob_internal(
                includes=['*.java'],
                excludes=[],
                include_dotfiles=False,
                allow_empty=True,
                search_base=search_base))

    def test_explicit_exclude_with_file_separator_excludes(self):
        search_base = self.fake_path(
            'foo',
            glob_results={'java/**/*.java': ['java/Include.java', 'java/Exclude.java']})
        self.assertGlobMatches(
            ['java/Include.java'],
            glob_internal(
                includes=['java/**/*.java'],
                excludes=['java/Exclude.java'],
                include_dotfiles=False,
                allow_empty=False,
                search_base=search_base))


class TestBuckPosix(TestBuckPlatformBase, unittest.TestCase):
    @staticmethod
    def fake_path(*args, **kwargs):
        return fake_path(FakePosixPath, *args, **kwargs)

    def assertGlobMatches(self, expected, actual):
        self.assertEqual(expected, actual)


class TestBuckWindows(TestBuckPlatformBase, unittest.TestCase):
    @staticmethod
    def fake_path(*args, **kwargs):
        return fake_path(FakeWindowsPath, *args, **kwargs)

    def assertGlobMatches(self, expected, actual):
        # Fix the path separator to make test writing easier
        fixed_expected = []
        for path in expected:
            fixed_expected.append(path.replace('/', '\\'))
        self.assertEqual(fixed_expected, actual)


class TestBuck(unittest.TestCase):
    def test_glob_double_star_integration(self):
        d = tempfile.mkdtemp()
        try:
            subdir = os.path.join(d, 'b', 'a', 'c', 'a')
            os.makedirs(subdir)
            f = open(os.path.join(subdir, 'A.java'), 'w')
            f.close()
            f = open(os.path.join(subdir, 'B.java'), 'w')
            f.close()
            f = open(os.path.join(subdir, 'Test.java'), 'w')
            f.close()
            f = open(os.path.join(subdir, '.tmp.java'), 'w')
            f.close()
            os.makedirs(os.path.join(subdir, 'NotAFile.java'))
            self.assertEquals(
                [
                    os.path.join('b', 'a', 'c', 'a', 'A.java'),
                    os.path.join('b', 'a', 'c', 'a', 'B.java'),
                ],
                glob_internal(
                    includes=['b/a/**/*.java'],
                    excludes=['**/*Test.java'],
                    include_dotfiles=False,
                    allow_empty=False,
                    search_base=Path(d)))
        finally:
            shutil.rmtree(d)

    def test_case_preserved(self):
        d = tempfile.mkdtemp()
        try:
            subdir = os.path.join(d, 'java')
            os.makedirs(subdir)
            open(os.path.join(subdir, 'Main.java'), 'w').close()
            self.assertEquals(
                [
                    os.path.join('java', 'Main.java'),
                ],
                glob_internal(
                    includes=['java/Main.java'],
                    excludes=[],
                    include_dotfiles=False,
                    allow_empty=False,
                    search_base=Path(d)))
        finally:
            shutil.rmtree(d)


if __name__ == '__main__':
    unittest.main()
