from .buck import (
    BuildFileContext,
    LazyBuildEnvPartial,
    flatten_dicts,
    get_mismatched_args,
    subdir_glob,
    host_info,
)
from .glob_watchman import format_watchman_query_params
from .glob_internal import path_component_contains_dot, glob_internal
from pathlib import Path, PurePosixPath, PureWindowsPath
import itertools
import os
import shutil
import tempfile
import unittest


class FakePathMixin(object):
    def glob(self, pattern):
        # Python glob supports unix paths on windows out of the box
        norm_pattern = pattern.replace('\\', '/')
        return self.glob_results.get(norm_pattern)

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


class TestBuckPlatform(unittest.TestCase):
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


class TestBuckGlobMixin(object):
    def do_glob(self, *args, **kwargs):
        # subclasses can override this to test a different glob implementation
        return glob_internal(*args, **kwargs)

    def test_glob_includes_simple(self):
        search_base = self.fake_path(
            'foo',
            glob_results={'*.java': ['A.java', 'B.java']})
        self.assertGlobMatches(
            ['A.java', 'B.java'],
            self.do_glob(
                includes=['*.java'],
                excludes=[],
                project_root_relative_excludes=[],
                include_dotfiles=False,
                search_base=search_base,
                project_root='.'))

    def test_glob_includes_sort(self):
        search_base = self.fake_path(
            'foo',
            glob_results={'*.java': ['A.java', 'E.java', 'D.java', 'C.java', 'B.java']})
        self.assertGlobMatches(
            ['A.java', 'B.java', 'C.java', 'D.java', 'E.java'],
            self.do_glob(
                includes=['*.java'],
                excludes=[],
                project_root_relative_excludes=[],
                include_dotfiles=False,
                search_base=search_base,
                project_root='.'))

    def test_glob_includes_multi(self):
        search_base = self.fake_path(
            'foo',
            glob_results={
                'bar/*.java': ['bar/A.java', 'bar/B.java'],
                'baz/*.java': ['baz/C.java', 'baz/D.java'],
            })
        self.assertGlobMatches(
            ['bar/A.java', 'bar/B.java', 'baz/C.java', 'baz/D.java'],
            self.do_glob(
                includes=['bar/*.java', 'baz/*.java'],
                excludes=[],
                project_root_relative_excludes=[],
                include_dotfiles=False,
                search_base=search_base,
                project_root='.'))

    def test_glob_excludes_double_star(self):
        search_base = self.fake_path(
            'foo',
            glob_results={
                '**/*.java': ['A.java', 'B.java', 'Test.java'],
            })
        self.assertGlobMatches(
            ['A.java', 'B.java'],
            self.do_glob(
                includes=['**/*.java'],
                excludes=['**/*Test.java'],
                project_root_relative_excludes=[],
                include_dotfiles=False,
                search_base=search_base,
                project_root='.'))

    def test_glob_excludes_multi(self):
        search_base = self.fake_path(
            'foo',
            glob_results={
                'bar/*.java': ['bar/A.java', 'bar/B.java'],
                'baz/*.java': ['baz/C.java', 'baz/D.java'],
            })
        self.assertGlobMatches(
            ['bar/B.java', 'baz/D.java'],
            self.do_glob(
                includes=['bar/*.java', 'baz/*.java'],
                excludes=['*/[AC].java'],
                project_root_relative_excludes=[],
                include_dotfiles=False,
                search_base=search_base,
                project_root='.'))

    def test_glob_excludes_relative(self):
        search_base = self.fake_path(
            'foo',
            glob_results={
                '**/*.java': ['foo/A.java', 'foo/bar/B.java', 'bar/C.java'],
            })
        self.assertGlobMatches(
            ['foo/A.java', 'foo/bar/B.java'],
            self.do_glob(
                includes=['**/*.java'],
                excludes=['bar/*.java'],
                project_root_relative_excludes=[],
                include_dotfiles=False,
                search_base=search_base,
                project_root='.'))

    def test_glob_project_root_relative_excludes_relative(self):
        search_base = self.fake_path(
            'foo',
            glob_results={
                '**/*.java': ['foo/A.java', 'foo/bar/B.java', 'bar/C.java'],
            })
        self.assertGlobMatches(
            ['bar/C.java'],
            self.do_glob(
                includes=['**/*.java'],
                excludes=[],
                project_root_relative_excludes=['foo/foo/**'],
                include_dotfiles=False,
                search_base=search_base,
                project_root='.'))

    def test_glob_includes_skips_dotfiles(self):
        search_base = self.fake_path(
            'foo',
            glob_results={'*.java': ['A.java', '.B.java']})
        self.assertGlobMatches(
            ['A.java'],
            self.do_glob(
                includes=['*.java'],
                excludes=[],
                project_root_relative_excludes=[],
                include_dotfiles=False,
                search_base=search_base,
                project_root='.'))

    def test_glob_includes_skips_dot_directories(self):
        search_base = self.fake_path(
            'foo',
            glob_results={'*.java': ['A.java', '.test/B.java']})
        self.assertGlobMatches(
            ['A.java'],
            self.do_glob(
                includes=['*.java'],
                excludes=[],
                project_root_relative_excludes=[],
                include_dotfiles=False,
                search_base=search_base,
                project_root='.'))

    def test_glob_includes_does_not_skip_dotfiles_if_include_dotfiles(self):
        search_base = self.fake_path(
            'foo',
            glob_results={'*.java': ['A.java', '.B.java']})
        self.assertGlobMatches(
            ['.B.java', 'A.java'],
            self.do_glob(
                includes=['*.java'],
                excludes=[],
                project_root_relative_excludes=[],
                include_dotfiles=True,
                search_base=search_base,
                project_root='.'))

    def test_explicit_exclude_with_file_separator_excludes(self):
        search_base = self.fake_path(
            'foo',
            glob_results={'java/**/*.java': ['java/Include.java', 'java/Exclude.java']})
        self.assertGlobMatches(
            ['java/Include.java'],
            self.do_glob(
                includes=['java/**/*.java'],
                excludes=['java/Exclude.java'],
                project_root_relative_excludes=[],
                include_dotfiles=False,
                search_base=search_base,
                project_root='.'))


class TestBuckSubdirGlobMixin(object):
    def do_subdir_glob(self, *args, **kwargs):
        # subclasses can override this to test a different glob implementation
        return subdir_glob(*args, **kwargs)

    def test_subdir_glob(self):
        build_env = BuildFileContext(
            self.fake_path(''), None, 'BUCK', None, None, None, [], None, None, None, None, False,
            False)
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
            self.do_subdir_glob([
                ('lib', 'bar/*.h'),
                ('lib', 'baz/*.h')],
                build_env=build_env,
                search_base=search_base))

    def test_subdir_glob_with_prefix(self):
        build_env = BuildFileContext(
            self.fake_path(''), None, 'BUCK', None, None, None, [], None, None, None, None, False,
            False)
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
            self.do_subdir_glob([('lib', 'bar/*.h')],
                        prefix='Prefix',
                        build_env=build_env,
                        search_base=search_base))


class TestBuckPosix(TestBuckGlobMixin, TestBuckSubdirGlobMixin, unittest.TestCase):
    @staticmethod
    def fake_path(*args, **kwargs):
        return fake_path(FakePosixPath, *args, **kwargs)

    def assertGlobMatches(self, expected, actual):
        self.assertEqual(expected, actual)


class TestBuckWindows(TestBuckGlobMixin, TestBuckSubdirGlobMixin, unittest.TestCase):
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
                    project_root_relative_excludes=[],
                    include_dotfiles=False,
                    search_base=Path(d),
                    project_root=Path(d)))
        finally:
            shutil.rmtree(d)

    def test_glob_star_does_not_zero_match_dotfile(self):
        # Verify the behavior of the "*." pattern. Note that in the shell,
        # "*." does not match dotfiles by default:
        #
        # $ ls ~/*.gitconfig
        # ls: /Users/mbolin/*.gitconfig: No such file or directory
        #
        # By comparison, glob() from the pathlib code in Python 3 will:
        #
        # >>> list(pathlib.Path(os.getenv('HOME')).glob('*.gitconfig'))
        # [PosixPath('/Users/mbolin/.gitconfig')]
        #
        # Buck should follow what the shell does here. Note this must also
        # hold true when Watchman is used to implement glob().
        d = tempfile.mkdtemp()
        try:
            a_subdir = os.path.join(d, 'a')
            os.makedirs(a_subdir)
            f = open(os.path.join(a_subdir, '.project.toml'), 'w')
            f.close()
            f = open(os.path.join(a_subdir, '..project.toml'), 'w')
            f.close()
            f = open(os.path.join(a_subdir, '.foo.project.toml'), 'w')
            f.close()
            f = open(os.path.join(a_subdir, 'Buck.project.toml'), 'w')
            f.close()

            b_subdir = os.path.join(d, 'b')
            os.makedirs(b_subdir)
            f = open(os.path.join(b_subdir, 'B.project.toml'), 'w')
            f.close()
            f = open(os.path.join(b_subdir, 'B..project.toml'), 'w')
            f.close()
            f = open(os.path.join(b_subdir, 'Buck.project.toml'), 'w')
            f.close()

            def do_glob(pattern, include_dotfiles):
                return glob_internal(
                    includes=[pattern],
                    excludes=[],
                    project_root_relative_excludes=[],
                    include_dotfiles=include_dotfiles,
                    search_base=Path(d),
                    project_root=Path(d))

            # Note that if include_dotfiles=False, the "*" in "*." will never
            # do a zero-length match or match a sequence that starts with "."
            # because "*." appears at the start of a path component boundary.
            self.assertEquals(
                [
                    os.path.join('a', 'Buck.project.toml'),
                ],
                do_glob('a/*.project.toml', include_dotfiles=False))
            self.assertEquals(
                [
                    os.path.join('a', '..project.toml'),
                    os.path.join('a', '.foo.project.toml'),
                    os.path.join('a', '.project.toml'),
                    os.path.join('a', 'Buck.project.toml'),
                ],
                do_glob('a/*.project.toml', include_dotfiles=True))

            # Note that "*." behaves differently if it is not at the start of a
            # path component boundary.
            self.assertEquals(
                [
                    os.path.join('b', 'B..project.toml'),
                    os.path.join('b', 'B.project.toml'),
                    os.path.join('b', 'Buck.project.toml'),
                ],
                do_glob('b/B*.project.toml', include_dotfiles=False))
            self.assertEquals(
                [
                    os.path.join('b', 'B..project.toml'),
                    os.path.join('b', 'B.project.toml'),
                    os.path.join('b', 'Buck.project.toml'),
                ],
                do_glob('b/B*.project.toml', include_dotfiles=True))
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
                    project_root_relative_excludes=[],
                    include_dotfiles=False,
                    search_base=Path(d),
                    project_root=Path(d)))
        finally:
            shutil.rmtree(d)

    def test_watchman_query_params_includes(self):
        query_params = format_watchman_query_params(
            ['**/*.java'],
            [],
            False,
            '/path/to/glob',
            False)
        self.assertEquals(
            {
                'relative_root': '/path/to/glob',
                'path': [''],
                'fields': ['name'],
                'expression': [
                    'allof',
                    ['anyof', ['type', 'f'], ['type', 'l']],
                    'exists',
                    ['anyof', ['match', '**/*.java', 'wholename', {}]],
                ]
            },
            query_params)

    def test_watchman_query_params_includes_and_excludes(self):
        query_params = format_watchman_query_params(
            ['**/*.java'],
            ['**/*Test.java'],
            False,
            '/path/to/glob',
            False)
        self.assertEquals(
            {
                'relative_root': '/path/to/glob',
                'path': [''],
                'fields': ['name'],
                'expression': [
                    'allof',
                    ['anyof', ['type', 'f'], ['type', 'l']],
                    ['not', ['anyof', ['match', '**/*Test.java', 'wholename', {}]]],
                    'exists',
                    ['anyof', ['match', '**/*.java', 'wholename', {}]],
                ]
            },
            query_params)

    def test_watchman_query_params_glob_generator(self):
        query_params = format_watchman_query_params(
            ['**/*.java'],
            ['**/*Test.java'],
            False,
            '/path/to/glob',
            True)
        self.assertEquals(
            {
                'relative_root': '/path/to/glob',
                'glob': ['**/*.java'],
                'glob_includedotfiles': False,
                'fields': ['name'],
                'expression': [
                    'allof',
                    ['anyof', ['type', 'f'], ['type', 'l']],
                    ['not', ['anyof', ['match', '**/*Test.java', 'wholename', {}]]],
                ]
            },
            query_params)

    def test_flatten_dicts_overrides_earlier_keys_with_later_ones(self):
        base = {
            'a': 'foo',
            'b': 'bar',
        }
        override = {
            'a': 'baz',
        }
        override2 = {
            'a': 42,
            'c': 'new',
        }
        self.assertEquals(
                {
                    'a': 'baz',
                    'b': 'bar',
                },
                flatten_dicts(base, override))
        self.assertEquals(
                {
                    'a': 42,
                    'b': 'bar',
                    'c': 'new',
                },
                flatten_dicts(base, override, override2)
        )
        # assert none of the input dicts were changed:
        self.assertEquals(
                {
                    'a': 'foo',
                    'b': 'bar',
                },
                base
        )
        self.assertEquals(
                {
                    'a': 'baz',
                },
                override
        )
        self.assertEquals(
                {
                    'a': 42,
                    'c': 'new',
                },
                override2
        )

    def test_path_component_contains_dot(self):
        self.assertFalse(path_component_contains_dot(Path('')))
        self.assertFalse(path_component_contains_dot(Path('foo')))
        self.assertFalse(path_component_contains_dot(Path('foo/bar')))
        self.assertTrue(path_component_contains_dot(Path('.foo/bar')))
        self.assertTrue(path_component_contains_dot(Path('foo/.bar')))
        self.assertTrue(path_component_contains_dot(Path('.foo/.bar')))


class TestHostInfo(unittest.TestCase):

    def test_returns_correct_os(self):

        test_data = {
            'Darwin': 'is_macos',
            'Windows': 'is_windows',
            'Linux': 'is_linux',
            'FreeBSD': 'is_freebsd',
            'blarg': 'is_unknown',
            'unknown': 'is_unknown',
        }

        for platform_value, expected_true_field in test_data.items():
            struct = host_info(
                platform_system=lambda: platform_value,
                platform_machine=lambda: 'x86_64')

            self.validate_host_info_struct(
                struct, 'os', expected_true_field, 'platform.system',
                platform_value)

    def test_returns_correct_arch(self):
        test_data = {
            'aarch64': 'is_aarch64',
            'arm': 'is_arm',
            'armeb': 'is_armeb',
            'i386': 'is_i386',
            'mips': 'is_mips',
            'mips64': 'is_mips64',
            'mipsel': 'is_mipsel',
            'mipsel64': 'is_mipsel64',
            'powerpc': 'is_powerpc',
            'ppc64': 'is_ppc64',
            'unknown': 'is_unknown',
            'blarg': 'is_unknown',
            'x86_64': 'is_x86_64',
            'amd64': 'is_x86_64',
            'arm64': 'is_aarch64',
        }

        for platform_value, expected_true_field in test_data.items():
            struct = host_info(
                platform_system=lambda: 'Darwin',
                platform_machine=lambda: platform_value)

            self.validate_host_info_struct(
                struct, 'arch', expected_true_field, 'platform.machine',
                platform_value)

    def validate_host_info_struct(
            self, struct, top_level, true_key, platform_func, platform_value):
        top_level_struct = getattr(struct, top_level)
        for field in top_level_struct._fields:
            if field == true_key:
                continue
            self.assertFalse(
                getattr(top_level_struct, field),
                'Expected {}.{} to be false in {} with {} returning '
                'value {}'.format(
                    top_level, field, struct, platform_func, platform_value))
        self.assertTrue(
            getattr(top_level_struct, true_key),
            'Expected {}.{} to be false in {} with {} returning '
            'value {}'.format(
                top_level, true_key, struct, platform_func, platform_value))


class TestMemoized(unittest.TestCase):
    def _makeone(self, func, *args, **kwargs):
        from .util import memoized
        return memoized(*args, **kwargs)(func)

    def test_cache_none(self):
        decorated = self._makeone(
            lambda _retval=iter([None, 'foo']): next(_retval))
        uncached = decorated()
        cached = decorated()
        self.assertEqual(uncached, cached)
        self.assertTrue(cached is None)

    def test_no_deepcopy(self):
        decorated = self._makeone(
            lambda: [],
            deepcopy=False,
        )
        initial = decorated()
        cached = decorated()
        self.assertTrue(initial is cached)

    def test_deepcopy(self):
        decorated = self._makeone(
            lambda: [{}],
        )
        initial = decorated()
        cached = decorated()
        self.assertTrue(initial is not cached)
        initial[0]['foo'] = 'bar'
        self.assertTrue(cached[0] == {})

    def test_cachekey(self):
        decorated = self._makeone(
            # note that in Python 2 without hash randomisation, 'bar' and 'baz' will collide in
            # a small dictionary, as their hash keys differ by 8.
            lambda foo, bar='baz', baz='bar', _retval=itertools.count(): next(_retval)
        )
        initial = decorated(42, baz='spam', bar='eggs')
        cached = decorated(42, bar='eggs', baz='spam')
        different_keyword_values = decorated(42, bar='eric', baz='idle')
        self.assertEqual(initial, cached)
        self.assertNotEqual(initial, different_keyword_values)

    def test_custom_cachekey(self):
        decorated = self._makeone(
            lambda foo, bar='baz', _retval=itertools.count(): next(_retval),
            keyfunc=lambda foo, **kwargs: foo,
        )
        initial = decorated(42, bar='spam')
        cached = decorated(42, bar='ignored')
        different_foo = decorated(81, bar='spam')
        self.assertEqual(initial, cached)
        self.assertNotEqual(initial, different_foo)

    def test_missing_foo(self):
        def fn(foo, bar=1, baz=None):
            pass
        missing, extra = get_mismatched_args(fn, [], {})
        self.assertEqual(missing, ['foo'])
        self.assertEqual(extra, [])

    def test_extra_kwargs(self):
        def fn(foo, bar=1, baz=None):
            pass
        missing, extra = get_mismatched_args(fn, [], {'parrot': 'dead', 'trout': 'slapped'})
        self.assertEqual(missing, ['foo'])
        self.assertEqual(extra, ['parrot', 'trout'])

    def test_foo_as_kwarg(self):
        def fn(foo, bar=1, baz=None):
            pass
        missing, extra = get_mismatched_args(fn, [], {'foo': 'value'})
        self.assertEqual(missing, [])
        self.assertEqual(extra, [])


if __name__ == '__main__':
    unittest.main()
