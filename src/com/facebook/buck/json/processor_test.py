import __builtin__
import contextlib
import os
import unittest
import shutil
import tempfile
import sys
import StringIO

from .buck import BuildFileProcessor, Diagnostic, add_rule


def foo_rule(name, srcs=[], visibility=[], build_env=None):
    add_rule({
        'buck.type': 'foo',
        'name': name,
        'srcs': srcs,
        'visibility': visibility,
    }, build_env)


def extract_from_results(name, results):
    for result in results:
        if result.keys() == [name]:
            return result[name]
    raise ValueError(str(results))


def get_includes_from_results(results):
    return extract_from_results('__includes', results)


def get_config_from_results(results):
    return extract_from_results('__configs', results)


def get_env_from_results(results):
    return extract_from_results('__env', results)


def setenv(varname, value=None):
    if value is None:
        os.environ.pop(varname, None)
    else:
        os.environ[varname] = value


@contextlib.contextmanager
def with_env(varname, value=None):
    saved = os.environ.get(varname)
    setenv(varname, value)
    try:
        yield
    finally:
        setenv(varname, saved)


@contextlib.contextmanager
def with_envs(envs):
    with contextlib.nested(*[with_env(n, v) for n, v in envs.iteritems()]):
        yield


class ProjectFile(object):

    def __init__(self, path, contents):
        self.path = path
        self.name = '//{0}'.format(path)
        if isinstance(contents, (tuple, list)):
            contents = os.linesep.join(contents) + os.linesep
        self.contents = contents


class BuckTest(unittest.TestCase):

    def setUp(self):
        self.project_root = tempfile.mkdtemp()
        self.allow_empty_globs = False
        self.build_file_name = 'BUCK'
        self.watchman_client = None
        self.watchman_error = None
        self.enable_build_file_sandboxing = False

    def tearDown(self):
        shutil.rmtree(self.project_root, True)

    def write_file(self, pfile):
        with open(os.path.join(self.project_root, pfile.path), 'w') as f:
            f.write(pfile.contents)

    def write_files(self, *pfiles):
        for pfile in pfiles:
            self.write_file(pfile)

    def create_build_file_processor(self, *includes, **kwargs):
        return BuildFileProcessor(
            self.project_root,
            self.project_root,  # watchman_watch_root
            None,               # watchman_project_prefix
            self.build_file_name,
            self.allow_empty_globs,
            False,              # ignore_buck_autodeps_files
            self.watchman_client,
            self.watchman_error,
            False,              # watchman_glob_stat_results
            self.enable_build_file_sandboxing,
            includes,
            **kwargs)

    def test_sibling_includes_use_separate_globals(self):
        """
        Test that consecutive includes can't see each others globals.

        If a build file includes two include defs, one after another, verify
        that the first's globals don't pollute the second's (e.g. the second
        cannot implicitly reference globals from the first without including
        it itself).
        """

        # Setup the includes defs.  The first one defines a variable that the
        # second one (incorrectly) implicitly references.
        include_def1 = ProjectFile(path='inc_def1', contents=('FOO = 1',))
        include_def2 = ProjectFile(path='inc_def2', contents=('BAR = FOO',))
        self.write_files(include_def1, include_def2)

        # Construct a processor using the above as default includes, and verify
        # that the second one can't use the first's globals.
        build_file = ProjectFile(path='BUCK', contents='')
        self.write_file(build_file)
        build_file_processor = self.create_build_file_processor(
            include_def1.name,
            include_def2.name)
        self.assertRaises(
            NameError,
            build_file_processor.process,
            build_file.path, set())

        # Construct a processor with no default includes, have a generated
        # build file include the include defs one after another, and verify
        # that the second one can't use the first's globals.
        build_file = ProjectFile(
            path='BUCK',
            contents=(
                'include_defs({0!r})'.format(include_def1.name),
                'include_defs({0!r})'.format(include_def2.name),
            ))
        self.write_file(build_file)
        build_file_processor = self.create_build_file_processor()
        self.assertRaises(
            NameError,
            build_file_processor.process,
            build_file.path, set())

    def test_lazy_include_defs(self):
        """
        Tests bug reported in https://github.com/facebook/buck/issues/182.

        If a include def references another include def via a lazy include_defs
        call is some defined function, verify that it can correctly access the
        latter's globals after the import.
        """

        # Setup the includes defs.  The first one defines a variable that the
        # second one references after a local 'include_defs' call.
        include_def1 = ProjectFile(path='inc_def1', contents=('FOO = 1',))
        include_def2 = ProjectFile(
            path='inc_def2',
            contents=(
                'def test():',
                '    include_defs({0!r})'.format(include_def1.name),
                '    FOO',
            ))
        self.write_files(include_def1, include_def2)

        # Construct a processor using the above as default includes, and verify
        # that the function 'test' can use 'FOO' after including the first
        # include def.
        build_file = ProjectFile(path='BUCK', contents=('test()',))
        self.write_file(build_file)
        build_file_processor = self.create_build_file_processor(
            include_def1.name,
            include_def2.name)
        build_file_processor.process(build_file.path, set())

        # Construct a processor with no default includes, have a generated
        # build file include the include defs one after another, and verify
        # that the function 'test' can use 'FOO' after including the first
        # include def.
        build_file = ProjectFile(
            path='BUCK',
            contents=(
                'include_defs({0!r})'.format(include_def1.name),
                'include_defs({0!r})'.format(include_def2.name),
                'test()',
            ))
        self.write_file(build_file)
        build_file_processor = self.create_build_file_processor()
        build_file_processor.process(build_file.path, set())

    def test_private_globals_are_ignored(self):
        """
        Verify globals prefixed with '_' don't get imported via 'include_defs'.
        """

        include_def = ProjectFile(path='inc_def1', contents=('_FOO = 1',))
        self.write_file(include_def)

        # Test we don't get private module attributes from default includes.
        build_file = ProjectFile(path='BUCK', contents=('_FOO',))
        self.write_file(build_file)
        build_file_processor = self.create_build_file_processor(
            include_def.name)
        self.assertRaises(
            NameError,
            build_file_processor.process,
            build_file.path, set())

        # Test we don't get private module attributes from explicit includes.
        build_file = ProjectFile(
            path='BUCK',
            contents=(
                'include_defs({0!r})'.format(include_def.name),
                '_FOO',
            ))
        self.write_file(build_file)
        build_file_processor = self.create_build_file_processor()
        self.assertRaises(
            NameError,
            build_file_processor.process,
            build_file.path, set())

    def test_implicit_includes_apply_to_explicit_includes(self):
        """
        Verify that implict includes are applied to explicit includes.
        """

        # Setup an implicit include that defines a variable, another include
        # that uses it, and a build file that uses the explicit include.
        implicit_inc = ProjectFile(path='implicit', contents=('FOO = 1',))
        explicit_inc = ProjectFile(path='explicit', contents=('FOO',))
        build_file = ProjectFile(
            path='BUCK',
            contents=(
                'include_defs({0!r})'.format(explicit_inc.name),
            ))
        self.write_files(implicit_inc, explicit_inc, build_file)

        # Run the processor to verify that the explicit include can use the
        # variable in the implicit include.
        build_file_processor = self.create_build_file_processor(
            implicit_inc.name)
        build_file_processor.process(build_file.path, set())

    def test_all_list_is_respected(self):
        """
        Verify that the `__all__` list in included files can be used to narrow
        what gets pulled in.
        """

        include_def = ProjectFile(
            path='inc_def1',
            contents=('__all__ = []', 'FOO = 1'))
        self.write_file(include_def)

        # Test we don't get non-whitelisted attributes from default includes.
        build_file = ProjectFile(path='BUCK', contents=('FOO',))
        self.write_file(build_file)
        build_file_processor = self.create_build_file_processor(
            include_def.name)
        self.assertRaises(
            NameError,
            build_file_processor.process,
            build_file.path, set())

        # Test we don't get non-whitelisted attributes from explicit includes.
        build_file = ProjectFile(
            path='BUCK',
            contents=(
                'include_defs({0!r})'.format(include_def.name),
                'FOO',
            ))
        self.write_file(build_file)
        build_file_processor = self.create_build_file_processor()
        self.assertRaises(
            NameError,
            build_file_processor.process,
            build_file.path, set())

    def test_do_not_override_overridden_builtins(self):
        """
        We want to ensure that if you override something like java_binary, and then use
        include_defs to get another file, you don't end up clobbering your override.
        """

        # Override java_library and have it automatically add a dep
        build_defs = ProjectFile(
            path='BUILD_DEFS',
            contents=(
                # While not strictly needed for this test, we want to make sure we are overriding
                # a provided method and not just defining it ourselves.
                'old_get_base_path = get_base_path',
                'def get_base_path(*args, **kwargs):',
                '  raise ValueError()',
                'include_defs("//OTHER_DEFS")',
            ))
        other_defs = ProjectFile(path='OTHER_DEFS', contents=())
        build_file = ProjectFile(
            path='BUCK',
            contents=(
                'get_base_path()',
            ))
        self.write_files(build_defs, other_defs, build_file)

        build_file_processor = self.create_build_file_processor(build_defs.name)
        build_file_processor.install_builtins(__builtin__.__dict__)
        self.assertRaises(
            ValueError,
            build_file_processor.process,
            build_file.path, set())

    def test_watchman_glob_failure_falls_back_to_regular_glob_and_adds_diagnostic(self):
        class FakeWatchmanError(Exception):
            pass

        class FakeWatchmanClient:
            def __init__(self):
                self.query_invoked = False

            def query(self, *args):
                self.query_invoked = True
                raise FakeWatchmanError("Nobody watches the watchmen")

            def close(self):
                pass

        self.watchman_client = FakeWatchmanClient()
        self.watchman_error = FakeWatchmanError

        build_file = ProjectFile(
            path='BUCK',
            contents=(
                'foo_rule(',
                '  name="foo",'
                '  srcs=glob(["*.java"]),',
                ')'
            ))
        java_file = ProjectFile(path='Foo.java', contents=())
        self.write_files(build_file, java_file)
        build_file_processor = self.create_build_file_processor(extra_funcs=[foo_rule])
        build_file_processor.install_builtins(__builtin__.__dict__)
        diagnostics = set()
        rules = build_file_processor.process(build_file.path, diagnostics)
        self.assertTrue(self.watchman_client.query_invoked)
        self.assertEqual(['Foo.java'], rules[0]['srcs'])
        self.assertEqual(
            set([Diagnostic(
                message='Nobody watches the watchmen',
                level='error',
                source='watchman')]),
            diagnostics)

    def test_watchman_glob_warning_adds_diagnostic(self):
        class FakeWatchmanClient:
            def query(self, *args):
                return {'warning': 'This is a warning', 'files': ['Foo.java']}

            def close(self):
                pass

        self.watchman_client = FakeWatchmanClient()

        build_file = ProjectFile(
            path='BUCK',
            contents=(
                'foo_rule(',
                '  name="foo",'
                '  srcs=glob(["*.java"]),',
                ')'
            ))
        java_file = ProjectFile(path='Foo.java', contents=())
        self.write_files(build_file, java_file)
        build_file_processor = self.create_build_file_processor(extra_funcs=[foo_rule])
        build_file_processor.install_builtins(__builtin__.__dict__)
        diagnostics = set()
        rules = build_file_processor.process(build_file.path, diagnostics)
        self.assertEqual(['Foo.java'], rules[0]['srcs'])
        self.assertEqual(
            set([Diagnostic(
                message='This is a warning',
                level='warning',
                source='watchman')]),
            diagnostics)

    def test_read_config(self):
        """
        Verify that the builtin `read_config()` function works.
        """

        build_file = ProjectFile(
            path='BUCK',
            contents=(
                'assert read_config("hello", "world") == "foo"',
                'assert read_config("hello", "bar") is None',
                'assert read_config("hello", "goo", "default") == "default"',
            ))
        self.write_file(build_file)
        build_file_processor = self.create_build_file_processor(
            configs={('hello', 'world'): 'foo'})
        result = build_file_processor.process(build_file.path, set())
        self.assertEquals(
            get_config_from_results(result),
            {'hello': {'world': 'foo', 'bar': None, 'goo': None}})

    def test_add_build_file_dep(self):
        """
        Test simple use of `add_build_file_dep`.
        """

        # Setup the build file and dependency.
        dep = ProjectFile(path='dep', contents=('',))
        build_file = (
            ProjectFile(
                path='BUCK',
                contents=(
                    'add_build_file_dep("//dep")',
                ),
            ))
        self.write_files(dep, build_file)

        # Create a process and run it.
        build_file_processor = self.create_build_file_processor()
        results = build_file_processor.process(build_file.path, set())

        # Verify that the dep was recorded.
        self.assertTrue(
            os.path.join(self.project_root, dep.path) in
            get_includes_from_results(results))

    def test_import_works_without_sandboxing(self):
        self.enable_build_file_sandboxing = False
        build_file = ProjectFile(
            path='BUCK',
            contents=(
                'import ssl',
            ))
        self.write_files(build_file)
        build_file_processor = self.create_build_file_processor()
        build_file_processor.install_builtins(__builtin__.__dict__)
        build_file_processor.process(build_file.path, set())

    def test_enabled_sandboxing_blocks_import(self):
        self.enable_build_file_sandboxing = True
        build_file = ProjectFile(
            path='BUCK',
            contents=(
                'import ssl',
            ))
        self.write_files(build_file)
        build_file_processor = self.create_build_file_processor()
        build_file_processor.install_builtins(__builtin__.__dict__)
        self.assertRaises(
            ImportError,
            build_file_processor.process,
            build_file.path, set())

    def test_allow_unsafe_import_allows_to_import(self):
        """
        Verify that `allow_unsafe_import()` allows to import specified modules
        """
        self.enable_build_file_sandboxing = True
        # Importing httplib results in `__import__()` calls for other modules, e.g. socket, sys
        build_file = ProjectFile(
            path='BUCK',
            contents=(
                'with allow_unsafe_import():',
                '    import math, httplib',
            ))
        self.write_files(build_file)
        build_file_processor = self.create_build_file_processor()
        build_file_processor.install_builtins(__builtin__.__dict__)
        build_file_processor.process(build_file.path, set())

    def test_modules_are_not_copied_unless_specified(self):
        """
        Test that modules are not copied by 'include_defs' unless specified in '__all__'.
        """

        include_def = ProjectFile(
            path='inc_def',
            contents=(
                'import math',
                'def math_pi():',
                '    return math.pi',
            ))
        self.write_files(include_def)

        # Module math should not be accessible
        build_file = ProjectFile(
            path='BUCK',
            contents=(
                'include_defs({0!r})'.format(include_def.name),
                'assert(round(math.pi, 2) == 3.14)',
            ))
        self.write_file(build_file)
        build_file_processor = self.create_build_file_processor()
        self.assertRaises(
            NameError,
            build_file_processor.process,
            build_file.path, set())

        # Confirm that math_pi() works
        build_file = ProjectFile(
            path='BUCK',
            contents=(
                'include_defs({0!r})'.format(include_def.name),
                'assert(round(math_pi(), 2) == 3.14)',
            ))
        self.write_file(build_file)
        build_file_processor = self.create_build_file_processor()
        build_file_processor.process(build_file.path, set())

        # If specified in '__all__', math should be accessible
        include_def = ProjectFile(
            path='inc_def',
            contents=(
                '__all__ = ["math"]',
                'import math',
            ))
        build_file = ProjectFile(
            path='BUCK',
            contents=(
                'include_defs({0!r})'.format(include_def.name),
                'assert(round(math.pi, 2) == 3.14)',
            ))
        self.write_files(include_def, build_file)
        build_file_processor = self.create_build_file_processor()
        build_file_processor.process(build_file.path, set())

    def test_os_getenv(self):
        """
        Verify that calling `os.getenv()` records the environment variable.
        """

        build_file = ProjectFile(
            path='BUCK',
            contents=(
                'import os',
                'assert os.getenv("TEST1") == "foo"',
                'assert os.getenv("TEST2") is None',
                'assert os.getenv("TEST3", "default") == "default"',
            ))
        self.write_file(build_file)
        with with_envs({'TEST1': 'foo', 'TEST2': None, 'TEST3': None}):
            build_file_processor = self.create_build_file_processor()
            with build_file_processor.with_env_interceptors():
                result = build_file_processor.process(build_file.path, set())
        self.assertEquals(
            get_env_from_results(result),
            {'TEST1': "foo", 'TEST2': None, 'TEST3': None})

    def test_os_environ(self):
        """
        Verify that accessing environemtn variables via `os.environ` records
        the environment variables.
        """

        build_file = ProjectFile(
            path='BUCK',
            contents=(
                'import os',
                'assert os.environ["TEST1"] == "foo"',
                'assert os.environ.get("TEST2") is None',
                'assert os.environ.get("TEST3", "default") == "default"',
                'assert "TEST4" in os.environ',
                'assert "TEST5" not in os.environ',
            ))
        self.write_file(build_file)
        build_file_processor = self.create_build_file_processor()
        with with_envs({'TEST1': 'foo', 'TEST2': None, 'TEST3': None, 'TEST4': '', 'TEST5': None}):
            build_file_processor = self.create_build_file_processor()
            with build_file_processor.with_env_interceptors():
                result = build_file_processor.process(build_file.path, set())
        self.assertEquals(
            get_env_from_results(result),
            {'TEST1': "foo", 'TEST2': None, 'TEST3': None, 'TEST4': '', 'TEST5': None})
