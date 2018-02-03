import __builtin__
import contextlib
import json
import os
import unittest
import shutil
import tempfile
import StringIO

from pywatchman import WatchmanError
from typing import Sequence

from .buck import BuildFileProcessor, BuildInclude, IncludeContext, Diagnostic, add_rule, \
    process_with_diagnostics


def foo_rule(name, srcs=None, visibility=None, options=None, some_optional=None, build_env=None):
    """A dummy build rule."""
    add_rule({
        'buck.type': 'foo',
        'name': name,
        'srcs': srcs or [],
        'options': options or {},
        'some_optional': some_optional,
        'visibility': visibility or [],
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

    def __init__(self, root, path, contents):
        # type: (str, str, Sequence[str]) -> None
        """Record of a file that can be written to disk.

        :param root: root.
        :param path: path to write the file, relative to the root.
        :param contents: lines of file context
        """
        self.path = path
        self.name = '//{0}'.format(path)
        self.load_name = '//{0}:{1}'.format(*os.path.split(path))
        self.root = root
        self.prefix = None
        if isinstance(contents, (tuple, list)):
            contents = os.linesep.join(contents) + os.linesep
        self.contents = contents


class BuckTest(unittest.TestCase):

    def setUp(self):
        self.project_root = tempfile.mkdtemp()
        self.allow_empty_globs = False
        self.cell_name = ''
        self.build_file_name = 'BUCK'
        self.watchman_client = None
        self.project_import_whitelist = None

    def tearDown(self):
        shutil.rmtree(self.project_root, True)

    def write_file(self, pfile):
        # type: (ProjectFile) -> None
        with open(os.path.join(self.project_root, pfile.path), 'w') as f:
            f.write(pfile.contents)

    def write_files(self, *pfiles):
        # type: (*ProjectFile) -> None
        for pfile in pfiles:
            self.write_file(pfile)

    def create_build_file_processor(self, cell_roots=None, includes=None, **kwargs):
        return BuildFileProcessor(
            self.project_root,
            cell_roots or {},
            self.cell_name,
            self.build_file_name,
            self.allow_empty_globs,
            self.watchman_client,
            False,              # watchman_glob_stat_results
            False,              # watchman_use_glob_generator
            self.project_import_whitelist,
            includes or [],
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
        include_def1 = ProjectFile(self.project_root, path='inc_def1', contents=('FOO = 1',))
        include_def2 = ProjectFile(self.project_root, path='inc_def2', contents=('BAR = FOO',))
        self.write_files(include_def1, include_def2)

        # Construct a processor using the above as default includes, and verify
        # that the second one can't use the first's globals.
        build_file = ProjectFile(self.project_root, path='BUCK', contents='')
        self.write_file(build_file)
        build_file_processor = self.create_build_file_processor(
            includes=[include_def1.name, include_def2.name])
        self.assertRaises(
            NameError,
            build_file_processor.process,
            build_file.root, build_file.prefix, build_file.path, set())

        # Construct a processor with no default includes, have a generated
        # build file include the include defs one after another, and verify
        # that the second one can't use the first's globals.
        build_file = ProjectFile(
            self.project_root,
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
            build_file.root, build_file.prefix, build_file.path, set())

    def test_lazy_include_defs(self):
        """
        Tests bug reported in https://github.com/facebook/buck/issues/182.

        If a include def references another include def via a lazy include_defs
        call is some defined function, verify that it can correctly access the
        latter's globals after the import.
        """

        # Setup the includes defs.  The first one defines a variable that the
        # second one references after a local 'include_defs' call.
        include_def1 = ProjectFile(self.project_root, path='inc_def1', contents=('FOO = 1',))
        include_def2 = ProjectFile(
            self.project_root,
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
        build_file = ProjectFile(self.project_root, path='BUCK', contents=('test()',))
        self.write_file(build_file)
        build_file_processor = self.create_build_file_processor(
            includes=[include_def1.name, include_def2.name])
        build_file_processor.process(build_file.root, build_file.prefix, build_file.path, [])

        # Construct a processor with no default includes, have a generated
        # build file include the include defs one after another, and verify
        # that the function 'test' can use 'FOO' after including the first
        # include def.
        build_file = ProjectFile(
            self.project_root,
            path='BUCK',
            contents=(
                'include_defs({0!r})'.format(include_def1.name),
                'include_defs({0!r})'.format(include_def2.name),
                'test()',
            ))
        self.write_file(build_file)
        build_file_processor = self.create_build_file_processor()
        build_file_processor.process(build_file.root, build_file.prefix, build_file.path, [])

    def test_private_globals_are_ignored(self):
        """
        Verify globals prefixed with '_' don't get imported via 'include_defs'.
        """

        include_def = ProjectFile(self.project_root, path='inc_def1', contents=('_FOO = 1',))
        self.write_file(include_def)

        # Test we don't get private module attributes from default includes.
        build_file = ProjectFile(self.project_root, path='BUCK', contents=('_FOO',))
        self.write_file(build_file)
        build_file_processor = self.create_build_file_processor(
            includes=[include_def.name])
        self.assertRaises(
            NameError,
            build_file_processor.process,
            build_file.root, build_file.prefix, build_file.path, [])

        # Test we don't get private module attributes from explicit includes.
        build_file = ProjectFile(
            self.project_root,
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
            build_file.root, build_file.prefix, build_file.path, [])

    def test_implicit_includes_apply_to_explicit_includes(self):
        """
        Verify that implict includes are applied to explicit includes.
        """

        # Setup an implicit include that defines a variable, another include
        # that uses it, and a build file that uses the explicit include.
        implicit_inc = ProjectFile(self.project_root, path='implicit', contents=('FOO = 1',))
        explicit_inc = ProjectFile(self.project_root, path='explicit', contents=('FOO',))
        build_file = ProjectFile(
            self.project_root,
            path='BUCK',
            contents=(
                'include_defs({0!r})'.format(explicit_inc.name),
            ))
        self.write_files(implicit_inc, explicit_inc, build_file)

        # Run the processor to verify that the explicit include can use the
        # variable in the implicit include.
        build_file_processor = self.create_build_file_processor(
            includes=[implicit_inc.name])
        build_file_processor.process(build_file.root, build_file.prefix, build_file.path, [])

    def test_all_list_is_respected(self):
        """
        Verify that the `__all__` list in included files can be used to narrow
        what gets pulled in.
        """

        include_def = ProjectFile(
            self.project_root,
            path='inc_def1',
            contents=('__all__ = []', 'FOO = 1'))
        self.write_file(include_def)

        # Test we don't get non-whitelisted attributes from default includes.
        build_file = ProjectFile(self.project_root, path='BUCK', contents=('FOO',))
        self.write_file(build_file)
        build_file_processor = self.create_build_file_processor(
            includes=[include_def.name])
        self.assertRaises(
            NameError,
            build_file_processor.process,
            build_file.root, build_file.prefix, build_file.path, [])

        # Test we don't get non-whitelisted attributes from explicit includes.
        build_file = ProjectFile(
            self.project_root,
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
            build_file.root, build_file.prefix, build_file.path, [])

    def test_do_not_override_overridden_builtins(self):
        """
        We want to ensure that if you override something like java_binary, and then use
        include_defs to get another file, you don't end up clobbering your override.
        """

        # Override java_library and have it automatically add a dep
        build_defs = ProjectFile(
            self.project_root,
            path='BUILD_DEFS',
            contents=(
                # While not strictly needed for this test, we want to make sure we are overriding
                # a provided method and not just defining it ourselves.
                'old_get_base_path = get_base_path',
                'def get_base_path(*args, **kwargs):',
                '  raise ValueError()',
                'include_defs("//OTHER_DEFS")',
            ))
        other_defs = ProjectFile(self.project_root, path='OTHER_DEFS', contents=())
        build_file = ProjectFile(
            self.project_root,
            path='BUCK',
            contents=(
                'get_base_path()',
            ))
        self.write_files(build_defs, other_defs, build_file)

        build_file_processor = self.create_build_file_processor(
            includes=[build_defs.name])
        with build_file_processor.with_builtins(__builtin__.__dict__):
            self.assertRaises(
                ValueError,
                build_file_processor.process,
                build_file.root, build_file.prefix, build_file.path, [])

    def test_watchman_glob_failure_raises_diagnostic_with_stack(self):
        class FakeWatchmanClient:
            def __init__(self):
                self.query_invoked = False

            def query(self, *args):
                self.query_invoked = True
                raise WatchmanError("Nobody watches the watchmen")

            def close(self):
                pass

        self.watchman_client = FakeWatchmanClient()

        build_file = ProjectFile(
            self.project_root,
            path='BUCK',
            contents=(
                'foo_rule(',
                '  name="foo",'
                '  srcs=glob(["*.java"]),',
                ')'
            ))
        java_file = ProjectFile(self.project_root, path='Foo.java', contents=())
        self.write_files(build_file, java_file)
        build_file_processor = self.create_build_file_processor(extra_funcs=[foo_rule])
        diagnostics = []
        rules = []
        fake_stdout = StringIO.StringIO()
        with build_file_processor.with_builtins(__builtin__.__dict__):
            self.assertRaises(
                WatchmanError,
                process_with_diagnostics,
                {
                    'buildFile': self.build_file_name,
                    'watchRoot': '',
                    'projectPrefix': self.project_root,
                },
                build_file_processor,
                fake_stdout)
        self.assertTrue(self.watchman_client.query_invoked)
        result = fake_stdout.getvalue()
        decoded_result = json.loads(result)
        self.assertEqual([], decoded_result['values'])
        self.assertEqual(1, len(decoded_result['diagnostics']))
        diagnostic = decoded_result['diagnostics'][0]
        self.assertEqual('fatal', diagnostic['level'])
        self.assertEqual('watchman', diagnostic['source'])
        self.assertEqual('Nobody watches the watchmen', diagnostic['message'])
        exception = diagnostic['exception']
        self.assertEqual('WatchmanError', exception['type'])
        self.assertEqual('Nobody watches the watchmen', exception['value'])
        self.assertTrue(len(exception['traceback']) > 0)

    def test_glob_exclude_is_supported(self):
        build_file = ProjectFile(
            self.project_root,
            path='BUCK',
            contents=(
                'foo_rule(',
                '  name="foo",'
                '  srcs=glob(["*.java"], exclude=[]),',
                ')'
            ))
        java_file = ProjectFile(self.project_root, path='Foo.java', contents=())
        self.write_files(build_file, java_file)
        build_file_processor = self.create_build_file_processor(extra_funcs=[foo_rule])
        diagnostics = []
        with build_file_processor.with_builtins(__builtin__.__dict__):
            rules = build_file_processor.process(
                build_file.root, build_file.prefix, build_file.path, diagnostics)
            self.assertEqual(rules[0].get('srcs'), ['Foo.java'])

    def test_glob_exclude_cannot_be_mixed_with_excludes(self):
        build_file = ProjectFile(
            self.project_root,
            path='BUCK',
            contents=(
                'foo_rule(',
                '  name="foo",'
                '  srcs=glob(["*.java"], exclude=["e1"], excludes=["e2"]),',
                ')'
            ))
        java_file = ProjectFile(self.project_root, path='Foo.java', contents=())
        self.write_files(build_file, java_file)
        build_file_processor = self.create_build_file_processor(extra_funcs=[foo_rule])
        diagnostics = []
        with build_file_processor.with_builtins(__builtin__.__dict__):
            self.assertRaisesRegexp(
                AssertionError,
                "Mixing 'exclude' and 'excludes' attributes is not allowed. Please replace your "
                "exclude and excludes arguments with a single 'excludes = \['e1', 'e2'\]'.",
                build_file_processor.process,
                build_file.root,
                build_file.prefix,
                build_file.path,
                diagnostics)

    def test_watchman_glob_warning_adds_diagnostic(self):
        class FakeWatchmanClient:
            def query(self, *args):
                return {'warning': 'This is a warning', 'files': ['Foo.java']}

            def close(self):
                pass

        self.watchman_client = FakeWatchmanClient()

        build_file = ProjectFile(
            self.project_root,
            path='BUCK',
            contents=(
                'foo_rule(',
                '  name="foo",'
                '  srcs=glob(["*.java"]),',
                ')'
            ))
        java_file = ProjectFile(self.project_root, path='Foo.java', contents=())
        self.write_files(build_file, java_file)
        build_file_processor = self.create_build_file_processor(extra_funcs=[foo_rule])
        diagnostics = []
        with build_file_processor.with_builtins(__builtin__.__dict__):
            rules = build_file_processor.process(
                build_file.root, build_file.prefix, build_file.path, diagnostics)
        self.assertEqual(['Foo.java'], rules[0]['srcs'])
        self.assertEqual(
            [Diagnostic(
                message='This is a warning',
                level='warning',
                source='watchman',
                exception=None)],
            diagnostics)

    def test_multiple_watchman_glob_warning_adds_diagnostics_in_order(self):
        warnings = iter(['Warning 1', 'Warning 2'])
        glob_results = iter([['Foo.java'], ['Foo.c']])

        class FakeWatchmanClient:
            def query(self, *args):
                return {'warning': warnings.next(), 'files': glob_results.next()}

            def close(self):
                pass

        self.watchman_client = FakeWatchmanClient()

        build_file = ProjectFile(
            self.project_root,
            path='BUCK',
            contents=(
                'foo_rule(',
                '  name="foo",'
                '  srcs=glob(["*.java"]) + glob(["*.c"]),',
                ')'
            ))
        java_file = ProjectFile(self.project_root, path='Foo.java', contents=())
        c_file = ProjectFile(self.project_root, path='Foo.c', contents=())
        self.write_files(build_file, java_file, c_file)
        build_file_processor = self.create_build_file_processor(extra_funcs=[foo_rule])
        with build_file_processor.with_builtins(__builtin__.__dict__):
            diagnostics = []
            rules = build_file_processor.process(
                build_file.root, build_file.prefix, build_file.path,
                diagnostics)
            self.assertEqual(['Foo.java', 'Foo.c'], rules[0]['srcs'])
            self.assertEqual(
                [Diagnostic(
                    message='Warning 1',
                    level='warning',
                    source='watchman',
                    exception=None),
                 Diagnostic(
                     message='Warning 2',
                     level='warning',
                     source='watchman',
                     exception=None)],
                diagnostics)

    def test_read_config(self):
        """
        Verify that the builtin `read_config()` function works.
        """

        build_file = ProjectFile(
            self.project_root,
            path='BUCK',
            contents=(
                'assert read_config("hello", "world") == "foo"',
                'assert read_config("hello", "bar") is None',
                'assert read_config("hello", "goo", "default") == "default"',
            ))
        self.write_file(build_file)
        build_file_processor = self.create_build_file_processor(
            configs={('hello', 'world'): 'foo'})
        result = build_file_processor.process(build_file.root, build_file.prefix, build_file.path,
                                              [])
        self.assertEquals(
            get_config_from_results(result),
            {'hello': {'world': 'foo', 'bar': None, 'goo': None}})

    def test_struct_is_available(self):
        extension_file = ProjectFile(
            self.project_root,
            path='ext.bzl',
            contents=(
                's = struct(name="foo")',
            )
        )
        build_file = ProjectFile(
            self.project_root,
            path='BUCK',
            contents=(
                'load("//:ext.bzl", "s")',
                'foo_rule(',
                '  name=s.name,',
                ')'
            ))
        self.write_files(extension_file, build_file)
        build_file_processor = self.create_build_file_processor(extra_funcs=[foo_rule])
        diagnostics = []
        with build_file_processor.with_builtins(__builtin__.__dict__):
            rules = build_file_processor.process(
                build_file.root, build_file.prefix, build_file.path, diagnostics)
            self.assertEqual(rules[0].get('name'), 'foo')

    def test_struct_to_json(self):
        extension_file = ProjectFile(
            self.project_root,
            path='ext.bzl',
            contents=(
                's = struct(name="foo")',
            )
        )
        build_file = ProjectFile(
            self.project_root,
            path='BUCK',
            contents=(
                'load("//:ext.bzl", "s")',
                'foo_rule(',
                '  name=s.to_json(),',
                ')'
            ))
        self.write_files(extension_file, build_file)
        build_file_processor = self.create_build_file_processor(extra_funcs=[foo_rule])
        diagnostics = []
        with build_file_processor.with_builtins(__builtin__.__dict__):
            rules = build_file_processor.process(
                build_file.root, build_file.prefix, build_file.path, diagnostics)
            self.assertEqual(rules[0].get('name'), '{"name":"foo"}')

    def test_provider_is_available(self):
        extension_file = ProjectFile(
            self.project_root,
            path='ext.bzl',
            contents=(
                'Info = provider()',
                'info = Info(name="foo")',
            )
        )
        build_file = ProjectFile(
            self.project_root,
            path='BUCK',
            contents=(
                'load("//:ext.bzl", "info")',
                'foo_rule(',
                '  name=info.name,',
                ')'
            ))
        self.write_files(extension_file, build_file)
        build_file_processor = self.create_build_file_processor(extra_funcs=[foo_rule])
        diagnostics = []
        with build_file_processor.with_builtins(__builtin__.__dict__):
            rules = build_file_processor.process(
                build_file.root, build_file.prefix, build_file.path, diagnostics)
            self.assertEqual(rules[0].get('name'), 'foo')

    def test_native_module_is_available(self):
        extension_file = ProjectFile(
            self.project_root,
            path='ext.bzl',
            contents=(
                'def get_sources():'
                '  return native.glob(["*"])',
            )
        )
        build_file = ProjectFile(
            self.project_root,
            path='BUCK',
            contents=(
                'load("//:ext.bzl", "get_sources")',
                'foo_rule(',
                '  name="foo",'
                '  srcs=get_sources()',
                ')'
            ))
        self.write_files(extension_file, build_file)
        build_file_processor = self.create_build_file_processor(extra_funcs=[foo_rule])
        diagnostics = []
        with build_file_processor.with_builtins(__builtin__.__dict__):
            rules = build_file_processor.process(
                build_file.root, build_file.prefix, build_file.path, diagnostics)
            self.assertEqual(rules[0].get('srcs'), ['BUCK', 'ext.bzl'])

    def test_package_name_is_available(self):
        package_dir = os.path.join(self.project_root, 'pkg')
        os.makedirs(package_dir)
        build_file = ProjectFile(
            self.project_root,
            path='pkg/BUCK',
            contents=(
                'foo_rule(',
                '  name=package_name(),',
                ')'
            ))
        self.write_file(build_file)
        build_file_processor = self.create_build_file_processor(extra_funcs=[foo_rule])
        diagnostics = []
        with build_file_processor.with_builtins(__builtin__.__dict__):
            rules = build_file_processor.process(
                build_file.root, build_file.prefix, build_file.path, diagnostics)
            self.assertEqual(rules[0].get('name'), 'pkg')

    def test_package_name_in_extension_returns_build_file_package(self):
        package_dir = os.path.join(self.project_root, 'pkg')
        os.makedirs(package_dir)
        extension_file = ProjectFile(
            self.project_root,
            path='ext.bzl',
            contents=(
                'def foo():',
                '  return package_name()',
            ))
        self.write_file(extension_file)
        build_file = ProjectFile(
            self.project_root,
            path='pkg/BUCK',
            contents=(
                'load("//:ext.bzl", "foo")',
                'foo_rule(',
                '  name=foo(),',
                ')'
            ))
        self.write_file(build_file)
        build_file_processor = self.create_build_file_processor(extra_funcs=[foo_rule])
        diagnostics = []
        with build_file_processor.with_builtins(__builtin__.__dict__):
            rules = build_file_processor.process(
                build_file.root, build_file.prefix, build_file.path, diagnostics)
            self.assertEqual(rules[0].get('name'), 'pkg')

    def test_add_build_file_dep(self):
        """
        Test simple use of `add_build_file_dep`.
        """

        # Setup the build file and dependency.
        dep = ProjectFile(self.project_root, path='dep', contents=('',))
        build_file = (
            ProjectFile(
                self.project_root,
                path='BUCK',
                contents=(
                    'add_build_file_dep("//dep")',
                ),
            ))
        self.write_files(dep, build_file)

        # Create a process and run it.
        build_file_processor = self.create_build_file_processor()
        results = build_file_processor.process(build_file.root, build_file.prefix, build_file.path,
                                               [])

        # Verify that the dep was recorded.
        self.assertTrue(
            os.path.join(self.project_root, dep.path) in
            get_includes_from_results(results))

    def test_imports_are_blocked(self):
        build_file = ProjectFile(
            self.project_root,
            path='BUCK',
            contents=(
                'import ssl',
            ))
        self.write_files(build_file)
        build_file_processor = self.create_build_file_processor()
        with build_file_processor.with_builtins(__builtin__.__dict__):
            self.assertRaises(
                ImportError,
                build_file_processor.process,
                build_file.root, build_file.prefix, build_file.path, [])

    def test_import_whitelist(self):
        """
        Verify that modules whitelisted globally or in configs can be imported
        with sandboxing enabled.
        """
        self.project_import_whitelist = ['sys', 'subprocess']
        build_file = ProjectFile(
            self.project_root,
            path='BUCK',
            contents=(
                'import json',
                'import functools',
                'import re',
                'import sys',
                'import subprocess',
            ))
        self.write_files(build_file)
        build_file_processor = self.create_build_file_processor()
        build_file_processor.process(build_file.root, build_file.prefix, build_file.path, [])

    def test_allow_unsafe_import_allows_to_import(self):
        """
        Verify that `allow_unsafe_import()` allows to import specified modules
        """
        # Importing httplib results in `__import__()` calls for other modules, e.g. socket, sys
        build_file = ProjectFile(
            self.project_root,
            path='BUCK',
            contents=(
                'with allow_unsafe_import():',
                '    import math, httplib',
            ))
        self.write_files(build_file)
        build_file_processor = self.create_build_file_processor()
        with build_file_processor.with_builtins(__builtin__.__dict__):
            build_file_processor.process(
                build_file.root,
                build_file.prefix,
                build_file.path,
                [])

    def test_modules_are_not_copied_unless_specified(self):
        """
        Test that modules are not copied by 'include_defs' unless specified in '__all__'.
        """

        include_def = ProjectFile(
            self.project_root,
            path='inc_def',
            contents=(
                'with allow_unsafe_import():',
                '    import math',
                '    def math_pi():',
                '        return math.pi',
            ))
        self.write_files(include_def)

        # Module math should not be accessible
        build_file = ProjectFile(
            self.project_root,
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
            build_file.root, build_file.prefix, build_file.path, [])

        # Confirm that math_pi() works
        build_file = ProjectFile(
            self.project_root,
            path='BUCK',
            contents=(
                'include_defs({0!r})'.format(include_def.name),
                'assert(round(math_pi(), 2) == 3.14)',
            ))
        self.write_file(build_file)
        build_file_processor = self.create_build_file_processor()
        build_file_processor.process(build_file.root, build_file.prefix, build_file.path, [])

        # If specified in '__all__', math should be accessible
        include_def = ProjectFile(
            self.project_root,
            path='inc_def',
            contents=(
                '__all__ = ["math"]',
                'with allow_unsafe_import():',
                '    import math',
            ))
        build_file = ProjectFile(
            self.project_root,
            path='BUCK',
            contents=(
                'include_defs({0!r})'.format(include_def.name),
                'assert(round(math.pi, 2) == 3.14)',
            ))
        self.write_files(include_def, build_file)
        build_file_processor = self.create_build_file_processor()
        build_file_processor.process(build_file.root, build_file.prefix, build_file.path, [])

    def test_os_getenv(self):
        """
        Verify that calling `os.getenv()` records the environment variable.
        """

        build_file = ProjectFile(
            self.project_root,
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
                result = build_file_processor.process(build_file.root, build_file.prefix,
                                                      build_file.path, [])
        self.assertEquals(
            get_env_from_results(result),
            {'TEST1': "foo", 'TEST2': None, 'TEST3': None})

    def test_os_environ(self):
        """
        Verify that accessing environemtn variables via `os.environ` records
        the environment variables.
        """

        build_file = ProjectFile(
            self.project_root,
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
                result = build_file_processor.process(build_file.root, build_file.prefix,
                                                      build_file.path, [])
        self.assertEquals(
            get_env_from_results(result),
            {'TEST1': "foo", 'TEST2': None, 'TEST3': None, 'TEST4': '', 'TEST5': None})

    def test_safe_modules_allow_safe_functions(self):
        """
        Test that 'import os.path' allows access to safe 'os' functions,
        'import pipes' allows 'quote' and also that 'from os.path import *' works.
        """

        build_file = ProjectFile(
            self.project_root,
            path='BUCK',
            contents=(
                'import os.path',
                'from os.path import *',
                'import pipes',
                'assert(os.path.split("a/b/c") == ("a/b", "c"))',
                'assert(split("a/b/c") == ("a/b", "c"))',
                'assert os.environ["TEST1"] == "foo"',
                'assert pipes.quote("foo; bar") == "\'foo; bar\'"'
            ))
        self.write_files(build_file)
        with with_envs({'TEST1': 'foo'}):
            build_file_processor = self.create_build_file_processor()
            build_file_processor.process(build_file.root, build_file.prefix, build_file.path,
                                         [])

    def test_safe_modules_block_unsafe_functions(self):
        """
        Test that after 'import os.path' unsafe functions raise errors
        """

        build_file = ProjectFile(
            self.project_root,
            path='BUCK',
            contents=(
                'import os.path',
                'os.path.exists("a/b")',
            ))
        self.write_files(build_file)
        build_file_processor = self.create_build_file_processor()
        # 'os.path.exists()' should raise AttributeError
        self.assertRaises(
            AttributeError,
            build_file_processor.process,
            build_file.root, build_file.prefix, build_file.path, [])

    def test_wrap_access_prints_warnings(self):
        path = os.path.normpath(os.path.join(self.project_root, 'foo.py'))
        build_file = ProjectFile(
            self.project_root,
            path='BUCK',
            contents=("open('{0}', 'r')".format(path.replace('\\', '\\\\')),))
        py_file = ProjectFile(self.project_root, path='foo.py', contents=('foo',))
        self.write_files(build_file, py_file)
        build_file_processor = self.create_build_file_processor()
        diagnostics = []
        build_file_processor.process(build_file.root, build_file.prefix, build_file.path,
                                     diagnostics)
        expected_message = (
            "Access to a non-tracked file detected! {0} is not a ".format(path) +
            "known dependency and it should be added using 'add_build_file_dep' " +
            "function before trying to access the file, e.g.\n" +
            "'add_build_file_dep({0!r})'\n".format(py_file.name) +
            "The 'add_build_file_dep' function is documented at " +
            "https://buckbuild.com/function/add_build_file_dep.html\n"
        )
        self.assertEqual(
            [Diagnostic(
                message=expected_message,
                level='warning',
                source='sandboxing',
                exception=None)],
            diagnostics)

    def test_can_resolve_cell_paths(self):
        build_file_processor = self.create_build_file_processor(
            cell_roots={
                'foo': os.path.abspath(os.path.join(self.project_root, '../cell'))
            })
        self.assertEqual(
            BuildInclude(cell_name='foo',
                         path=os.path.abspath(os.path.join(self.project_root, '../cell/bar/baz'))),
            build_file_processor._resolve_include('foo//bar/baz'))
        self.assertEqual(
            BuildInclude(cell_name='',
                         path=os.path.abspath(os.path.join(self.project_root, 'bar/baz'))),
            build_file_processor._resolve_include('//bar/baz'))

    def test_load_path_is_resolved(self):
        extension_file = ProjectFile(
            self.project_root,
            path='ext.bzl',
            contents=(
                's = struct(name="loaded_name")',
            )
        )
        build_file = ProjectFile(
            self.project_root,
            path='BUCK',
            contents=(
                'load("//:ext.bzl", "s")',
                'foo_rule(',
                '  name=s.name,',
                ')'
            ))
        self.write_files(extension_file, build_file)
        build_file_processor = self.create_build_file_processor(extra_funcs=[foo_rule])
        with build_file_processor.with_builtins(__builtin__.__dict__):
            result = build_file_processor.process(self.project_root, None, 'BUCK', [])
            foo_target = result[0]
            self.assertEqual(foo_target['name'], 'loaded_name')

    def test_load_path_with_cell_is_resolved(self):
        build_file_processor = self.create_build_file_processor(
            cell_roots={
                'foo': os.path.abspath(os.path.join(self.project_root, '../cell'))
            })
        build_file_processor._current_build_env = IncludeContext('foo', 'some_lib.bzl')
        self.assertEqual(
            BuildInclude(cell_name='foo',
                         path=os.path.abspath(os.path.join(self.project_root, '../cell/bar/baz'))),
            build_file_processor._get_load_path('foo//bar:baz'))

    def test_load_path_with_skylark_style_cell_is_resolved(self):
        build_file_processor = self.create_build_file_processor(
            cell_roots={
                'foo': os.path.abspath(os.path.join(self.project_root, '../cell'))
            })
        self.assertEqual(
            BuildInclude(cell_name='foo',
                         path=os.path.abspath(os.path.join(self.project_root, '../cell/bar/baz'))),
            build_file_processor._get_load_path('@foo//bar:baz'))

    def test_json_encoding_failure(self):
        build_file_processor = self.create_build_file_processor(extra_funcs=[foo_rule])
        fake_stdout = StringIO.StringIO()
        build_file = ProjectFile(
            self.project_root,
            path='BUCK',
            contents=(
                'foo_rule(',
                '  name="foo",'
                '  srcs=[object()],'
                ')'
            ))
        self.write_file(build_file)
        with build_file_processor.with_builtins(__builtin__.__dict__):
            process_with_diagnostics(
                {
                    'buildFile': self.build_file_name,
                    'watchRoot': '',
                    'projectPrefix': self.project_root,
                },
                build_file_processor,
                fake_stdout)
        result = fake_stdout.getvalue()
        decoded_result = json.loads(result)
        self.assertEqual(
            [],
            decoded_result['values'])
        self.assertEqual(
            'fatal',
            decoded_result['diagnostics'][0]['level'])
        self.assertEqual(
            'parse',
            decoded_result['diagnostics'][0]['source'])

    def test_explicitly_loaded_values_are_available(self):
        defs_file = ProjectFile(
            root=self.project_root,
            path='DEFS',
            contents=(
                'value = 3',
                'another_value = 4',
            )
        )
        build_file = ProjectFile(
            self.project_root,
            path='BUCK',
            contents=(
                'load("//:DEFS", "value")',
                'foo_rule(name="foo" + str(value), srcs=[])',
            )
        )
        self.write_files(defs_file, build_file)
        processor = self.create_build_file_processor(extra_funcs=[foo_rule])
        with processor.with_builtins(__builtin__.__dict__):
            result = processor.process(self.project_root, None, 'BUCK', [])
            self.assertTrue(
                [x for x in result if x.get('name', '') == 'foo3'],
                "result should contain rule with name derived from an explicitly loaded value",
            )

    def test_globals_not_explicitly_loaded_are_not_available(self):
        defs_file = ProjectFile(
            root=self.project_root,
            path='DEFS',
            contents=(
                'value = 3',
                'another_value = 4',
            )
        )
        build_file = ProjectFile(
            self.project_root,
            path='BUCK_fail',
            contents=(
                'load("//:DEFS", "value")',
                'foo_rule(name="foo" + str(another_value), srcs=[])',
            )
        )
        self.write_files(defs_file, build_file)

        processor = self.create_build_file_processor(extra_funcs=[foo_rule])
        with processor.with_builtins(__builtin__.__dict__):
            self.assertRaises(
                NameError,
                lambda: processor.process(self.project_root, None, 'BUCK_fail', []))

    def test_can_rename_loaded_global(self):
        defs_file = ProjectFile(
            root=self.project_root,
            path='DEFS',
            contents=(
                'value = 3',
                'another_value = 4',
            )
        )
        build_file = ProjectFile(
            self.project_root,
            path='BUCK',
            contents=(
                'load("//:DEFS", bar="value")',
                'foo_rule(name="foo" + str(bar), srcs=[])',
            )
        )
        self.write_files(defs_file, build_file)
        processor = self.create_build_file_processor(extra_funcs=[foo_rule])
        with processor.with_builtins(__builtin__.__dict__):
            result = processor.process(self.project_root, None, 'BUCK', [])
            self.assertTrue(
                [x for x in result if x.get('name', '') == 'foo3'],
                "result should contain rule with name derived from an explicitly loaded value",
            )

    def test_original_symbol_is_not_available_when_renamed(self):
        defs_file = ProjectFile(
            root=self.project_root,
            path='DEFS',
            contents='value = 3',
        )
        build_file = ProjectFile(
            self.project_root,
            path='BUCK_fail',
            contents=(
                'load("//:DEFS", bar="value")',
                'foo_rule(name="foo" + str(value), srcs=[])',
            )
        )
        self.write_files(defs_file, build_file)
        processor = self.create_build_file_processor(extra_funcs=[foo_rule])
        with processor.with_builtins(__builtin__.__dict__):
            self.assertRaises(
                NameError,
                lambda: processor.process(self.project_root, None, 'BUCK_fail', []))

    def test_cannot_load_non_existent_symbol(self):
        defs_file = ProjectFile(
            root=self.project_root,
            path='DEFS',
            contents=(
                'value = 3',
            )
        )
        build_file = ProjectFile(
            self.project_root,
            path='BUCK_fail',
            contents=(
                'load("//:DEFS", "bar")',
                'foo_rule(name="foo" + str(bar), srcs=[])',
            )
        )
        self.write_files(defs_file, build_file)

        processor = self.create_build_file_processor(extra_funcs=[foo_rule])

        expected_msg = '"bar" is not defined in ' + os.path.join(self.project_root, defs_file.path)
        with processor.with_builtins(__builtin__.__dict__):
            with self.assertRaises(KeyError) as e:
                processor.process(self.project_root, None, 'BUCK_fail', [])
            self.assertEqual(e.exception.message,
                             expected_msg)

    def test_cannot_load_non_existent_symbol_by_keyword(self):
        defs_file = ProjectFile(
            root=self.project_root,
            path='DEFS',
            contents=(
                'value = 3',
            )
        )
        build_file = ProjectFile(
            self.project_root,
            path='BUCK_fail',
            contents=(
                'load("//:DEFS", value="bar")',
                'foo_rule(name="foo" + str(value), srcs=[])',
            )
        )
        self.write_files(defs_file, build_file)

        processor = self.create_build_file_processor(extra_funcs=[foo_rule])

        expected_msg = '"bar" is not defined in ' + os.path.join(self.project_root, defs_file.path)
        with processor.with_builtins(__builtin__.__dict__):
            with self.assertRaises(KeyError) as e:
                processor.process(self.project_root, None, 'BUCK_fail', [])
            self.assertEqual(e.exception.message, expected_msg)

    def test_fail_function_throws_an_error(self):
        build_file = ProjectFile(
            root=self.project_root,
            path='BUCK_fail',
            contents=(
                'fail("expected error")',
            )
        )
        self.write_files(build_file)

        processor = self.create_build_file_processor()

        with processor.with_builtins(__builtin__.__dict__):
            with self.assertRaisesRegexp(AssertionError, "expected error"):
                processor.process(self.project_root, None, 'BUCK_fail', [])

    def test_fail_function_includes_attribute_information(self):
        build_file = ProjectFile(
            root=self.project_root,
            path='BUCK_fail',
            contents=(
                'fail("error", "foo")',
            )
        )
        self.write_files(build_file)

        processor = self.create_build_file_processor()

        with processor.with_builtins(__builtin__.__dict__):
            with self.assertRaisesRegexp(AssertionError, "attribute foo: error"):
                processor.process(self.project_root, None, 'BUCK_fail', [])

    def test_values_from_namespaced_includes_accessible_only_via_namespace(self):
        defs_file = ProjectFile(
            root=self.project_root,
            path='DEFS',
            contents=(
                'value = 2',
            )
        )
        build_file = ProjectFile(
            self.project_root,
            path='BUCK',
            contents=(
                'include_defs("//DEFS", "defs")',
                'foo_rule(name="foo" + str(defs.value), srcs=[])',
            )
        )
        self.write_files(defs_file, build_file)
        processor = self.create_build_file_processor(extra_funcs=[foo_rule])
        with processor.with_builtins(__builtin__.__dict__):
            result = processor.process(self.project_root, None, 'BUCK', [])
        self.assertTrue(
            [x for x in result if x.get('name', '') == 'foo2'],
            "result should contain rule with name derived from a value in namespaced defs",
        )
        # should not be in global scope
        self.write_file(ProjectFile(
            self.project_root,
            path='BUCK_fail',
            contents=(
                'include_defs("//DEFS", "defs")',
                'foo_rule(name="foo" + str(value), srcs=[])',
            )
        ))
        with processor.with_builtins(__builtin__.__dict__):
            self.assertRaises(
                NameError,
                lambda: processor.process(self.project_root, None, 'BUCK_fail', []))

    def test_json_encoding_skips_None(self):
        build_file_processor = self.create_build_file_processor(extra_funcs=[foo_rule])
        fake_stdout = StringIO.StringIO()
        build_file = ProjectFile(
            self.project_root,
            path='BUCK',
            contents=(
                """
foo_rule(
  name="foo",
  srcs=['Foo.java'],
)
"""
            ))
        java_file = ProjectFile(self.project_root, path='Foo.java', contents=())
        self.write_files(build_file, java_file)
        with build_file_processor.with_builtins(__builtin__.__dict__):
            process_with_diagnostics(
                {
                    'buildFile': self.build_file_name,
                    'watchRoot': '',
                    'projectPrefix': self.project_root,
                },
                build_file_processor,
                fake_stdout)
        result = fake_stdout.getvalue()
        decoded_result = json.loads(result)
        self.assertNotIn('some_optional', decoded_result['values'][0])
        self.assertIn('srcs', decoded_result['values'][0])

    def test_json_encoding_list_like_object(self):
        build_file_processor = self.create_build_file_processor(extra_funcs=[foo_rule])
        fake_stdout = StringIO.StringIO()
        build_file = ProjectFile(
            self.project_root,
            path='BUCK',
            contents=(
                """
import collections

class ListLike(collections.MutableSequence):
  def __init__(self, list):
    self.list = list
  def __delitem__(self, key):
    self.list.__delitems__(key)
  def __getitem__(self, key):
    return self.list.__getitem__(key)
  def __setitem__(self, key, value):
    self.list.__setitem__(key, value)
  def __len__(self):
    return self.list.__len__()
  def insert(self, position, value):
    self.list.insert(position, value)

foo_rule(
  name="foo",
  srcs=ListLike(['Foo.java','Foo.c']),
)
"""
            ))
        java_file = ProjectFile(self.project_root, path='Foo.java', contents=())
        c_file = ProjectFile(self.project_root, path='Foo.c', contents=())
        self.write_files(build_file, java_file, c_file)
        with build_file_processor.with_builtins(__builtin__.__dict__):
            process_with_diagnostics(
                {
                    'buildFile': self.build_file_name,
                    'watchRoot': '',
                    'projectPrefix': self.project_root,
                },
                build_file_processor,
                fake_stdout)
        result = fake_stdout.getvalue()
        decoded_result = json.loads(result)
        self.assertEqual(
            [],
            decoded_result.get('diagnostics', []))
        self.assertEqual(
            [u'Foo.java', u'Foo.c'],
            decoded_result['values'][0].get('srcs', []))

    def test_json_encoding_dict_like_object(self):
        build_file_processor = self.create_build_file_processor(extra_funcs=[foo_rule])
        fake_stdout = StringIO.StringIO()
        build_file = ProjectFile(
            self.project_root,
            path='BUCK',
            contents=(
                """
import collections

class DictLike(collections.MutableMapping):
  def __init__(self, dict):
    self.dict = dict
  def __delitem__(self, key):
    self.dict.__delitems__(key)
  def __getitem__(self, key):
    return self.dict.__getitem__(key)
  def __setitem__(self, key, value):
    self.dict.__setitem__(key, value)
  def __len__(self):
    return self.dict.__len__()
  def __iter__(self):
    return self.dict.__iter__()
  def insert(self, position, value):
    self.dict.insert(position, value)

foo_rule(
  name="foo",
  srcs=[],
  options=DictLike({'foo':'bar','baz':'blech'}),
)
"""
            ))
        self.write_file(build_file)
        with build_file_processor.with_builtins(__builtin__.__dict__):
            process_with_diagnostics(
                {
                    'buildFile': self.build_file_name,
                    'watchRoot': '',
                    'projectPrefix': self.project_root,
                },
                build_file_processor,
                fake_stdout)
        result = fake_stdout.getvalue()
        decoded_result = json.loads(result)
        self.assertEqual(
            [],
            decoded_result.get('diagnostics', []))
        self.assertEqual(
            {u'foo': u'bar', u'baz': u'blech'},
            decoded_result['values'][0].get('options', {}))

    def test_sort_keys(self):
        build_file_processor = self.create_build_file_processor(extra_funcs=[foo_rule])
        fake_stdout = StringIO.StringIO()
        build_file = ProjectFile(
            self.project_root,
            path='BUCK',
            contents=(
                """
foo_rule(
  name="foo",
  srcs=[],
  options={'foo':'bar','baz':'blech'},
)
"""
            ))
        self.write_file(build_file)
        with build_file_processor.with_builtins(__builtin__.__dict__):
            process_with_diagnostics(
                {
                    'buildFile': self.build_file_name,
                    'watchRoot': '',
                    'projectPrefix': self.project_root,
                },
                build_file_processor,
                fake_stdout)
        result = fake_stdout.getvalue()
        self.assertEquals(
            '{"values": [{"buck.base_path": "", "buck.type": "foo", "name": '
            '"foo", "options": {"baz": "blech", "foo": "bar"}, "srcs": [], '
            '"visibility": []}, {"__includes": ["BUCK"]}, {"__configs": {}}, '
            '{"__env": {}}]}',
            result)

    def test_file_parsed_as_build_file_and_include_def(self):
        """
        Test that paths can be parsed as both build files and include defs.
        """

        # Setup a build file, foo, and another build file bar which includes
        # the former.
        foo = ProjectFile(self.project_root, path='foo', contents=('FOO = 1',))
        bar = (
            ProjectFile(
                self.project_root,
                path='bar',
                contents=('include_defs({0!r})'.format(foo.name))))
        self.write_files(foo, bar)

        # Parse bar, which parses foo as an include def, then parse foo as a
        # build file.
        build_file_processor = self.create_build_file_processor()
        build_file_processor.process(bar.root, bar.prefix, bar.path, [])
        build_file_processor.process(foo.root, foo.prefix, foo.path, [])

    def test_load_with_implicit_includes(self):
        """
        Test a `load()` statement inside an implicit include.
        """

        # Setup the includes defs.  The second just includes the first one via
        # the `load()` function.
        include_def1 = ProjectFile(self.project_root, path='inc_def1', contents=())
        include_def2 = (
            ProjectFile(
                self.project_root,
                path='inc_def2',
                contents=(
                    'load({0!r})'.format(include_def1.load_name),
                )))
        self.write_files(include_def1, include_def2)

        # Construct a processor using the above as default includes, and run
        # it to verify nothing crashes.
        build_file = ProjectFile(self.project_root, path='BUCK', contents='')
        self.write_file(build_file)
        build_file_processor = (
            self.create_build_file_processor(includes=[include_def2.name]))
        build_file_processor.process(build_file.root, build_file.prefix, build_file.path, [])


if __name__ == '__main__':
    unittest.main()
