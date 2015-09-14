from buck import format_watchman_query_params, glob_internal, LazyBuildEnvPartial
from buck import subdir_glob, BuildFileContext
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
                search_base=search_base))

    def test_subdir_glob(self):
        build_env = BuildFileContext(None, None, None, None, None, None, None, None)
        search_base = self.fake_path(
            'foo',
            glob_results={
                'lib/bar/*.h': ['lib/bar/A.h', 'lib/bar/B.h'],
                'lib/baz/*.h': ['lib/baz/C.h', 'lib/baz/D.h'],
            })
        self.assertGlobMatches(
            {
                'bar/B.h': 'lib/bar/B.h',
                'bar/A.h': 'lib/bar/A.h',
                'baz/D.h': 'lib/baz/D.h',
                'baz/C.h': 'lib/baz/C.h',
            },
            subdir_glob([
                ('lib', 'bar/*.h'),
                ('lib', 'baz/*.h')],
                build_env=build_env,
                search_base=search_base))

    def test_subdir_glob_with_prefix(self):
        build_env = BuildFileContext(None, None, None, None, None, None, None, None)
        search_base = self.fake_path(
            'foo',
            glob_results={
                'lib/bar/*.h': ['lib/bar/A.h', 'lib/bar/B.h'],
            })
        self.assertGlobMatches(
            {
                'Prefix/bar/B.h': 'lib/bar/B.h',
                'Prefix/bar/A.h': 'lib/bar/A.h',
            },
            subdir_glob([('lib', 'bar/*.h')],
                        prefix='Prefix',
                        build_env=build_env,
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
        fixed_expected = None
        if isinstance(expected, list):
            fixed_expected = []
            for path in expected:
                fixed_expected.append(path.replace('/', '\\'))
        else:
            fixed_expected = {}
            for key, value in expected.items():
                fixed_expected.update({key.replace('/', '\\'): value.replace('/', '\\')})
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
                    search_base=Path(d)))
        finally:
            shutil.rmtree(d)

    def test_watchman_query_params_includes(self):
        query_params = format_watchman_query_params(
            ['**/*.java'],
            [],
            False,
            '/path/to/glob')
        self.assertEquals(
            {
                'relative_root': '/path/to/glob',
                'path': [''],
                'fields': ['name'],
                'expression': [
                    'allof',
                    'exists',
                    ['anyof', ['type', 'f'], ['type', 'l']],
                    ['anyof', ['match', '**/*.java', 'wholename', {}]],
                ]
            },
            query_params)

    def test_watchman_query_params_includes_and_excludes(self):
        query_params = format_watchman_query_params(
            ['**/*.java'],
            ['**/*Test.java'],
            False,
            '/path/to/glob')
        self.assertEquals(
            {
                'relative_root': '/path/to/glob',
                'path': [''],
                'fields': ['name'],
                'expression': [
                    'allof',
                    'exists',
                    ['anyof', ['type', 'f'], ['type', 'l']],
                    ['anyof', ['match', '**/*.java', 'wholename', {}]],
                    ['not', ['anyof', ['match', '**/*Test.java', 'wholename', {}]]],
                ]
            },
            query_params)


if __name__ == '__main__':
    unittest.main()
