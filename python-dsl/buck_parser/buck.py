from __future__ import absolute_import
from __future__ import division
from __future__ import print_function
from __future__ import with_statement

import __builtin__
import __future__

import contextlib
import collections
from pathlib import Path, PurePath
from pywatchman import WatchmanError
from .deterministic_set import DeterministicSet
from .json_encoder import BuckJSONEncoder
from .glob_internal import glob_internal
from .glob_watchman import SyncCookieState, glob_watchman
from .util import Diagnostic, cygwin_adjusted_path, get_caller_frame, is_special, is_in_dir
from .module_whitelist import ImportWhitelistManager
from .profiler import Profiler
from .struct import Struct

import abc
import functools
import imp
import inspect
import json
import optparse
import os
import os.path
import platform
import pywatchman
import re
import select
import sys
import time
import traceback
import types
from typing import Any, Dict, List, Tuple, Optional

try:
    # Python 2.6, 2.7, use iterator filter from Python 3
    from future_builtins import filter
except ImportError:
    # use standard filter (Python 3, Python < 2.6)
    pass

# When build files are executed, the functions in this file tagged with
# @provide_for_build will be provided in the build file's local symbol table.
# Those tagged with @provide_as_native_rule will be present unless
# explicitly disabled by parser.native_rules_enabled_in_build_files
#
# When these functions are called from a build file, they will be passed
# a keyword parameter, build_env, which is a object with information about
# the environment of the build file which is currently being processed.
# It contains the following attributes:
#
# "dirname" - The directory containing the build file.
#
# "base_path" - The base path of the build file.
#
# "cell_name" - The cell name the build file is in.

BUILD_FUNCTIONS = []
NATIVE_FUNCTIONS = []

# Wait this many seconds on recv() or send() in the pywatchman client
# if not otherwise specified in .buckconfig
DEFAULT_WATCHMAN_QUERY_TIMEOUT = 60.0

ORIGINAL_IMPORT = __builtin__.__import__


class AbstractContext(object):
    """Superclass of execution contexts."""
    __metaclass__ = abc.ABCMeta

    @abc.abstractproperty
    def includes(self):
        """
        :rtype: set[str]
        """
        raise NotImplementedError()

    @abc.abstractproperty
    def used_configs(self):
        """
        :rtype: dict[Tuple[str, str], str]
        """
        raise NotImplementedError()

    @abc.abstractproperty
    def used_env_vars(self):
        """
        :rtype: dict[str, str]
        """
        raise NotImplementedError()

    @abc.abstractproperty
    def diagnostics(self):
        """
        :rtype: list[Diagnostic]
        """
        raise NotImplementedError()

    def merge(self, other):
        """Merge the context of an included file into the current context.

        :param IncludeContext other: the include context to merge.
        :rtype: None
        """
        self.includes.update(other.includes)
        self.diagnostics.extend(other.diagnostics)
        self.used_configs.update(other.used_configs)
        self.used_env_vars.update(other.used_env_vars)


class BuildFileContext(AbstractContext):
    """The build context used when processing a build file."""

    def __init__(self, project_root, base_path, path, dirname, cell_name, allow_empty_globs,
                 ignore_paths, watchman_client, watchman_watch_root, watchman_project_prefix,
                 sync_cookie_state, watchman_glob_stat_results,
                 watchman_use_glob_generator):
        self.globals = {}
        self._includes = set()
        self._used_configs = {}
        self._used_env_vars = {}
        self._diagnostics = []
        self.rules = {}

        self.project_root = project_root
        self.base_path = base_path
        self.path = path
        self.cell_name = cell_name
        self.dirname = dirname
        self.allow_empty_globs = allow_empty_globs
        self.ignore_paths = ignore_paths
        self.watchman_client = watchman_client
        self.watchman_watch_root = watchman_watch_root
        self.watchman_project_prefix = watchman_project_prefix
        self.sync_cookie_state = sync_cookie_state
        self.watchman_glob_stat_results = watchman_glob_stat_results
        self.watchman_use_glob_generator = watchman_use_glob_generator

    @property
    def includes(self):
        return self._includes

    @property
    def used_configs(self):
        return self._used_configs

    @property
    def used_env_vars(self):
        return self._used_env_vars

    @property
    def diagnostics(self):
        return self._diagnostics


class IncludeContext(AbstractContext):
    """The build context used when processing an include."""

    def __init__(self, cell_name, path):
        """
        :param cell_name: a cell name of the current context. Note that this cell name can be
            different from the one BUCK file is evaluated in, since it can load extension files
            from other cells, which should resolve their loads relative to their own location.
        """
        self.cell_name = cell_name
        self.path = path
        self.globals = {}
        self._includes = set()
        self._used_configs = {}
        self._used_env_vars = {}
        self._diagnostics = []

    @property
    def includes(self):
        return self._includes

    @property
    def used_configs(self):
        return self._used_configs

    @property
    def used_env_vars(self):
        return self._used_env_vars

    @property
    def diagnostics(self):
        return self._diagnostics


BuildInclude = collections.namedtuple("BuildInclude", ["cell_name", "path"])


class LazyBuildEnvPartial(object):
    """Pairs a function with a build environment in which it will be executed.

    Note that while the function is specified via the constructor, the build
    environment must be assigned after construction, for the build environment
    currently being used.

    To call the function with its build environment, use the invoke() method of
    this class, which will forward the arguments from invoke() to the
    underlying function.
    """

    def __init__(self, func):
        self.func = func
        self.build_env = None

    def invoke(self, *args, **kwargs):
        """Invokes the bound function injecting 'build_env' into **kwargs."""
        updated_kwargs = kwargs.copy()
        updated_kwargs.update({'build_env': self.build_env})
        try:
            return self.func(*args, **updated_kwargs)
        except TypeError:
            missing_args, extra_args = get_mismatched_args(self.func, args, updated_kwargs)
            if missing_args or extra_args:
                name = '[missing]'
                if 'name' in updated_kwargs:
                    name = updated_kwargs['name']
                elif len(args) > 0:
                    # Optimistically hope that name is the first arg. It generally is...
                    name = args[0]
                raise IncorrectArgumentsException(
                    self.func.func_name, name, missing_args, extra_args)
            raise


HostInfoOs = collections.namedtuple(
    'HostInfoOs',
    [
        'is_linux',
        'is_macos',
        'is_windows',
        'is_freebsd',
        'is_unknown',
    ],
)

HostInfoArch = collections.namedtuple(
    'HostInfoArch',
    [
        'is_aarch64',
        'is_arm',
        'is_armeb',
        'is_i386',
        'is_mips',
        'is_mips64',
        'is_mipsel',
        'is_mipsel64',
        'is_powerpc',
        'is_ppc64',
        'is_unknown',
        'is_x86_64',
    ]
)

HostInfo = collections.namedtuple('HostInfo', ['os', 'arch'])


__supported_oses = {
    'darwin': 'macos',
    'windows': 'windows',
    'linux': 'linux',
    'freebsd': 'freebsd',
}

# Pulled from com.facebook.buck.util.environment.Architecture.java as
# possible values. amd64 and arm64 are remapped, but they may not
# actually be present on most systems
__supported_archs = {
    'aarch64': 'aarch64',
    'arm': 'arm',
    'armeb': 'armeb',
    'i386': 'i386',
    'mips': 'mips',
    'mips64': 'mips64',
    'mipsel': 'mipsel',
    'mipsel64': 'mipsel64',
    'powerpc': 'powerpc',
    'ppc64': 'ppc64',
    'unknown': 'unknown',
    'x86_64': 'x86_64',
    'amd64': 'x86_64',
    'arm64': 'aarch64',
}


def host_info(
        platform_system=platform.system,
        platform_machine=platform.machine):

    host_arch = __supported_archs.get(platform_machine().lower(), 'unknown')
    host_os = __supported_oses.get(platform_system().lower(), 'unknown')
    return HostInfo(
        os=HostInfoOs(
            is_linux=(host_os == 'linux'),
            is_macos=(host_os == 'macos'),
            is_windows=(host_os == 'windows'),
            is_freebsd=(host_os == 'freebsd'),
            is_unknown=(host_os == 'unknown'),
        ),
        arch=HostInfoArch(
            is_aarch64=(host_arch == 'aarch64'),
            is_arm=(host_arch == 'arm'),
            is_armeb=(host_arch == 'armeb'),
            is_i386=(host_arch == 'i386'),
            is_mips=(host_arch == 'mips'),
            is_mips64=(host_arch == 'mips64'),
            is_mipsel=(host_arch == 'mipsel'),
            is_mipsel64=(host_arch == 'mipsel64'),
            is_powerpc=(host_arch == 'powerpc'),
            is_ppc64=(host_arch == 'ppc64'),
            is_unknown=(host_arch == 'unknown'),
            is_x86_64=(host_arch == 'x86_64'),
        ),
    )


_cached_host_info = host_info()


def get_mismatched_args(func, actual_args, actual_kwargs):
    argspec = inspect.getargspec(func)

    required_args = set()
    all_acceptable_args = []
    for i, arg in enumerate(argspec.args):
        if i < (len(argspec.args) - len(argspec.defaults)):
            required_args.add(arg)
        all_acceptable_args.append(arg)

    extra_kwargs = set(actual_kwargs.keys()) - set(all_acceptable_args)

    for k in set(actual_kwargs.keys()) - extra_kwargs:
        all_acceptable_args.remove(k)

    not_supplied_args = all_acceptable_args[len(actual_args):]

    missing_args = [arg for arg in not_supplied_args if arg in required_args]
    return missing_args, sorted(list(extra_kwargs))


class IncorrectArgumentsException(TypeError):
    def __init__(self, func_name, name_arg, missing_args, extra_args):
        self.missing_args = missing_args
        self.extra_args = extra_args

        message = 'Incorrect arguments to %s with name %s:' % (func_name, name_arg)
        if missing_args:
            message += ' Missing required args: %s' % (', '.join(missing_args),)
        if extra_args:
            message += ' Extra unknown kwargs: %s' % (', '.join(extra_args),)

        super(IncorrectArgumentsException, self).__init__(message)


def provide_as_native_rule(func):
    NATIVE_FUNCTIONS.append(func)
    return func


def provide_for_build(func):
    BUILD_FUNCTIONS.append(func)
    return func


def add_rule(rule, build_env):
    """Record a rule in the current context.

    This should be invoked by rule functions generated by the Java code.

    :param dict rule: dictionary of the rule's fields.
    :param build_env: the current context.
    """
    assert isinstance(build_env, BuildFileContext), (
        "Cannot use `{}()` at the top-level of an included file."
        .format(rule['buck.type']))

    # Include the base path of the BUCK file so the reader consuming this
    # output will know which BUCK file the rule came from.
    if 'name' not in rule:
        raise ValueError(
            'rules must contain the field \'name\'.  Found %s.' % rule)
    rule_name = rule['name']
    if not isinstance(rule_name, basestring):
        raise ValueError(
            'rules \'name\' field must be a string.  Found %s.' % rule_name)

    if rule_name in build_env.rules:
        raise ValueError('Duplicate rule definition found.  Found %s and %s' %
                         (rule, build_env.rules[rule_name]))
    rule['buck.base_path'] = build_env.base_path

    build_env.rules[rule_name] = rule


def glob(includes, excludes=None, include_dotfiles=False, build_env=None, search_base=None,
         allow_safe_import=None):
    if excludes is None:
        excludes = []
    assert isinstance(build_env, BuildFileContext), (
        "Cannot use `glob()` at the top-level of an included file.")
    # Ensure the user passes lists of strings rather than just a string.
    assert not isinstance(includes, basestring), \
        "The first argument to glob() must be a list of strings."
    assert not isinstance(excludes, basestring), \
        "The excludes argument must be a list of strings."

    if search_base is None:
        search_base = Path(build_env.dirname)

    results = None
    if not includes:
        results = []
    elif build_env.watchman_client:
        results = glob_watchman(
            includes,
            excludes,
            include_dotfiles,
            build_env.base_path,
            build_env.watchman_watch_root,
            build_env.watchman_project_prefix,
            build_env.sync_cookie_state,
            build_env.watchman_client,
            build_env.diagnostics,
            build_env.watchman_glob_stat_results,
            build_env.watchman_use_glob_generator)
        if results:
            # glob should consistently return paths of type str, but
            # watchman client returns unicode instead.
            # Extra check is added to make this conversion resilient to
            # wachman API changes.
            results = [
                res.encode("utf-8") if isinstance(res, unicode) else res
                for res in results
            ]

    if results is None:
        results = glob_internal(
            includes,
            excludes,
            build_env.ignore_paths,
            include_dotfiles,
            search_base,
            build_env.project_root)
    assert build_env.allow_empty_globs or results, (
        "glob(includes={includes}, excludes={excludes}, include_dotfiles={include_dotfiles}) " +
        "returned no results.  (allow_empty_globs is set to false in the Buck " +
        "configuration)").format(
            includes=includes,
            excludes=excludes,
            include_dotfiles=include_dotfiles)

    return results


def merge_maps(*header_maps):
    result = {}
    for header_map in header_maps:
        for key in header_map:
            if key in result and result[key] != header_map[key]:
                assert False, 'Conflicting header files in header search paths. ' + \
                              '"%s" maps to both "%s" and "%s".' \
                              % (key, result[key], header_map[key])

            result[key] = header_map[key]

    return result


def single_subdir_glob(dirpath, glob_pattern, excludes=None, prefix=None, build_env=None,
                       search_base=None, allow_safe_import=None):
    if excludes is None:
        excludes = []
    results = {}
    files = glob([os.path.join(dirpath, glob_pattern)],
                 excludes=excludes,
                 build_env=build_env,
                 search_base=search_base,
                 allow_safe_import=allow_safe_import)
    for f in files:
        if dirpath:
            key = f[len(dirpath) + 1:]
        else:
            key = f
        if prefix:
            # `f` is a string, but we need to create correct platform-specific Path.
            # This method is called by tests for both posix style paths and
            # windows style paths.
            # When running tests, search_base is always set
            # and happens to have the correct platform-specific Path type.
            cls = PurePath if not search_base else type(search_base)
            key = str(cls(prefix) / cls(key))
        results[key] = f

    return results


def subdir_glob(glob_specs, excludes=None, prefix=None, build_env=None, search_base=None,
                allow_safe_import=None):
    """
    Given a list of tuples, the form of (relative-sub-directory, glob-pattern),
    return a dict of sub-directory relative paths to full paths.  Useful for
    defining header maps for C/C++ libraries which should be relative the given
    sub-directory.

    If prefix is not None, prepends it it to each key in the dictionary.
    """
    if excludes is None:
        excludes = []

    results = []

    for dirpath, glob_pattern in glob_specs:
        results.append(
            single_subdir_glob(dirpath, glob_pattern, excludes, prefix, build_env, search_base,
                               allow_safe_import=allow_safe_import))

    return merge_maps(*results)


@provide_for_build
def get_base_path(build_env=None):
    """Get the base path to the build file that was initially evaluated.

    This function is intended to be used from within a build defs file that
    likely contains macros that could be called from any build file.
    Such macros may need to know the base path of the file in which they
    are defining new build rules.

    :return: a string, such as "java/com/facebook". Note there is no
             trailing slash. The return value will be "" if called from
             the build file in the root of the project.
    :rtype: str
    """
    assert isinstance(build_env, BuildFileContext), (
        "Cannot use `get_base_path()` at the top-level of an included file.")
    return build_env.base_path


@provide_for_build
def package_name(build_env=None):
    """The name of the package being evaluated.

    For example, in the BUCK file "some/package/BUCK", its value will be
    "some/package".
    If the BUCK file calls a function defined in a *.bzl file, package_name()
    will return the package of the calling BUCK file. For example, if there is
    a BUCK file at "some/package/BUCK" and "some/other/package/ext.bzl"
    extension file, when BUCK file calls a function inside of ext.bzl file
    it will still return "some/package" and not "some/other/package".

    This function is intended to be used from within a build defs file that
    likely contains macros that could be called from any build file.
    Such macros may need to know the base path of the file in which they
    are defining new build rules.

    :return: a string, such as "java/com/facebook". Note there is no
             trailing slash. The return value will be "" if called from
             the build file in the root of the project.
    :rtype: str
    """
    return get_base_path(build_env=build_env)


@provide_for_build
def fail(message, attr=None, build_env=None):
    """Raises a parse error.

    :param message: Error message to display for the user.
        The object is converted to a string.
    :param attr: Optional name of the attribute that caused the error.
    """
    attribute_prefix = ("attribute " + attr + ": " if attr is not None else "")
    msg = attribute_prefix + str(message)
    raise AssertionError(msg)


@provide_for_build
def get_cell_name(build_env=None):
    """Get the cell name of the build file that was initially evaluated.

    This function is intended to be used from within a build defs file that
    likely contains macros that could be called from any build file.
    Such macros may need to know the base path of the file in which they
    are defining new build rules.

    :return: a string, such as "cell". The return value will be "" if
             the build file does not have a cell
             :rtype: str

    """
    assert isinstance(build_env, BuildFileContext), (
        "Cannot use `get_cell_name()` at the top-level of an included file.")
    return build_env.cell_name


def flatten_list_of_dicts(list_of_dicts):
    """Flatten the given list of dictionaries by merging l[1:] onto
    l[0], one at a time. Key/Value pairs which appear in later list entries
    will override those that appear in earlier entries

    :param list_of_dicts: the list of dict objects to flatten.
    :return: a single dict containing the flattened list
    """
    return_value = {}
    for d in list_of_dicts:
        for k, v in d.iteritems():
            return_value[k] = v
    return return_value


@provide_for_build
def flatten_dicts(*args, **_):
    """Flatten the given list of dictionaries by merging args[1:] onto
    args[0], one at a time.

    :param *args: the list of dict objects to flatten.
    :param **_: ignore the build_env kwarg
    :return: a single dict containing the flattened list
    """
    return flatten_list_of_dicts(args)


@provide_for_build
def depset(elements, build_env=None):
    """Creates an instance of sets with deterministic iteration order.
    :param elements: the list of elements constituting the returned depset.
    :rtype: DeterministicSet
    """
    return DeterministicSet(elements)


GENDEPS_SIGNATURE = re.compile(r'^#@# GENERATED FILE: DO NOT MODIFY ([a-f0-9]{40}) #@#\n$')


class BuildFileProcessor(object):
    """Handles the processing of a single build file.

    :type _current_build_env: AbstractContext | None
    """

    SAFE_MODULES_CONFIG = {
        'os': ['environ', 'getenv', 'path', 'sep', 'pathsep', 'linesep'],
        'os.path': ['basename', 'commonprefix', 'dirname', 'isabs', 'join', 'normcase',
                    'relpath', 'split', 'splitdrive', 'splitext', 'sep', 'pathsep'],
        'pipes': ['quote'],
    }

    def __init__(self, project_root, cell_roots, cell_name, build_file_name, allow_empty_globs,
                 watchman_client, watchman_glob_stat_results,
                 watchman_use_glob_generator,
                 project_import_whitelist=None, implicit_includes=None,
                 extra_funcs=None, configs=None, env_vars=None,
                 ignore_paths=None, disable_implicit_native_rules=False,
                 warn_about_deprecated_syntax=True):
        if project_import_whitelist is None:
            project_import_whitelist = []
        if implicit_includes is None:
            implicit_includes = []
        if extra_funcs is None:
            extra_funcs = []
        if configs is None:
            configs = {}
        if env_vars is None:
            env_vars = {}
        if ignore_paths is None:
            ignore_paths = []
        self._include_cache = {}
        self._current_build_env = None
        self._sync_cookie_state = SyncCookieState()

        self._project_root = project_root
        self._cell_roots = cell_roots
        self._cell_name = cell_name
        self._build_file_name = build_file_name
        self._implicit_includes = implicit_includes
        self._allow_empty_globs = allow_empty_globs
        self._watchman_client = watchman_client
        self._watchman_glob_stat_results = watchman_glob_stat_results
        self._watchman_use_glob_generator = watchman_use_glob_generator
        self._configs = configs
        self._env_vars = env_vars
        self._ignore_paths = ignore_paths
        self._disable_implicit_native_rules = disable_implicit_native_rules
        self._warn_about_deprecated_syntax = warn_about_deprecated_syntax

        lazy_global_functions = {}
        lazy_native_functions = {}
        for func in BUILD_FUNCTIONS + extra_funcs:
            func_with_env = LazyBuildEnvPartial(func)
            lazy_global_functions[func.__name__] = func_with_env
        for func in NATIVE_FUNCTIONS:
            func_with_env = LazyBuildEnvPartial(func)
            lazy_native_functions[func.__name__] = func_with_env

        self._global_functions = lazy_global_functions
        self._native_functions = lazy_native_functions
        self._import_whitelist_manager = ImportWhitelistManager(
            import_whitelist=self._create_import_whitelist(project_import_whitelist),
            safe_modules_config=self.SAFE_MODULES_CONFIG,
            path_predicate=lambda path: is_in_dir(path, self._project_root))

    def _create_native_module(self):
        """
        Creates a native module exposing built-in Buck rules.

        This module allows clients to refer to built-in Buck rules using
        "native.<native_rule>" syntax in their build files. For example,
        "native.java_library(...)" will use a native Java library rule.

        :return: 'native' module struct.
        """
        native_globals = {}
        self._install_builtins(native_globals, force_native_rules=True)
        assert 'glob' not in native_globals
        assert 'host_info' not in native_globals
        native_globals['glob'] = self._glob
        native_globals['host_info'] = self._host_info
        native_module_type = collections.namedtuple('native', native_globals.keys())
        return native_module_type(**native_globals)

    def _wrap_env_var_read(self, read, real):
        """
        Return wrapper around function that reads an environment variable so
        that the read is recorded.
        """

        @functools.wraps(real)
        def wrapper(varname, *arg, **kwargs):
            self._record_env_var(varname, read(varname))
            return real(varname, *arg, **kwargs)

        # Save the real function for restoration.
        wrapper._real = real

        return wrapper

    @contextlib.contextmanager
    def _with_env_interceptor(self, read, obj, *attrs):
        """
        Wrap a function, found at `obj.attr`, that reads an environment
        variable in a new function which records the env var read.
        """

        orig = []
        for attr in attrs:
            real = getattr(obj, attr)
            wrapped = self._wrap_env_var_read(read, real)
            setattr(obj, attr, wrapped)
            orig.append((attr, real))
        try:
            yield
        finally:
            for attr, real in orig:
                setattr(obj, attr, real)

    @contextlib.contextmanager
    def with_env_interceptors(self):
        """
        Install environment variable read interceptors into all known ways that
        a build file can access the environment.
        """

        # Use a copy of the env to provide a function to get at the low-level
        # environment.  The wrappers will use this when recording the env var.
        read = dict(os.environ).get

        # Install interceptors into the main ways a user can read the env.
        with self._with_env_interceptor(
            read, os.environ, '__contains__', '__getitem__', 'get'
        ):
            yield

    @staticmethod
    def _merge_explicit_globals(src, dst, whitelist=None, whitelist_mapping=None):
        # type: (types.ModuleType, Dict[str, Any], List[str], Dict[str, str]) -> None
        """Copy explicitly requested global definitions from one globals dict to another.

        If whitelist is set, only globals from the whitelist will be pulled in.
        If whitelist_mapping is set, globals will be exported under the name of the keyword. For
        example, foo="bar" would mean that a variable with name "bar" in imported file, will be
        available as "foo" in current file.
        """

        if whitelist is not None:
            for symbol in whitelist:
                if symbol not in src.__dict__:
                    raise KeyError("\"%s\" is not defined in %s" % (symbol, src.__name__))
                dst[symbol] = src.__dict__[symbol]

        if whitelist_mapping is not None:
            for exported_name, symbol in whitelist_mapping.iteritems():
                if symbol not in src.__dict__:
                    raise KeyError("\"%s\" is not defined in %s" % (symbol, src.__name__))
                dst[exported_name] = src.__dict__[symbol]

    def _merge_globals(self, mod, dst):
        # type: (types.ModuleType, Dict[str, Any]) -> None
        """Copy the global definitions from one globals dict to another.

        Ignores special attributes and attributes starting with '_', which
        typically denote module-level private attributes.
        """

        hidden = set([
            'include_defs',
            'load',
        ])

        keys = getattr(mod, '__all__', mod.__dict__.keys())

        for key in keys:
            # Block copying modules unless they were specified in '__all__'
            block_copying_module = not hasattr(mod, '__all__') and isinstance(
                mod.__dict__[key], types.ModuleType)
            if not key.startswith('_') and key not in hidden and not block_copying_module:
                dst[key] = mod.__dict__[key]

    def _update_functions(self, build_env):
        """
        Updates the build functions to use the given build context when called.
        """

        for function in self._global_functions.itervalues():
            function.build_env = build_env
        for function in self._native_functions.itervalues():
            function.build_env = build_env

    def _install_builtins(self, namespace, force_native_rules=False):
        """
        Installs the build functions, by their name, into the given namespace.
        """

        for name, function in self._global_functions.iteritems():
            namespace[name] = function.invoke
        if not self._disable_implicit_native_rules or force_native_rules:
            for name, function in self._native_functions.iteritems():
                namespace[name] = function.invoke

    @contextlib.contextmanager
    def with_builtins(self, namespace):
        """
        Installs the build functions for the duration of a `with` block.
        """

        original_namespace = namespace.copy()
        self._install_builtins(namespace)
        try:
            yield
        finally:
            namespace.clear()
            namespace.update(original_namespace)

    def _resolve_include(self, name):
        # type: (str) -> BuildInclude
        """Resolve the given include def name to a BuildInclude metadata."""
        match = re.match(r'^([A-Za-z0-9_]*)//(.*)$', name)
        if match is None:
            raise ValueError(
                'include_defs argument {} should be in the form of '
                '//path or cellname//path'.format(name))
        cell_name = match.group(1)
        relative_path = match.group(2)
        if len(cell_name) > 0:
            cell_root = self._cell_roots.get(cell_name)
            if cell_root is None:
                raise KeyError(
                    'include_defs argument {} references an unknown cell named {}'
                    'known cells: {!r}'.format(name, cell_name, self._cell_roots))
            return BuildInclude(cell_name=cell_name,
                                path=os.path.normpath(os.path.join(cell_root, relative_path)))
        else:
            return BuildInclude(cell_name=cell_name,
                                path=os.path.normpath(
                                    os.path.join(self._project_root, relative_path)))

    def _get_load_path(self, label):
        # type: (str) -> BuildInclude
        """Resolve the given load function label to a BuildInclude metadata."""
        match = re.match(r'^(@?[A-Za-z0-9_]+)?//(.*):(.*)$', label)
        if match is None:
            raise ValueError(
                'load label {} should be in the form of '
                '//path:file or cellname//path:file'.format(label))
        cell_name = match.group(1)
        if cell_name:
            if cell_name.startswith('@'):
                cell_name = cell_name[1:]
            elif self._warn_about_deprecated_syntax:
                self._emit_warning('{} has a load label "{}" that uses a deprecated cell format. '
                                   '"{}" should instead be "@{}".'.format(
                                        self._current_build_env.path, label, cell_name,
                                        cell_name), 'load function')
        else:
            cell_name = self._current_build_env.cell_name
        relative_path = match.group(2)
        file_name = match.group(3)
        if cell_name:
            cell_root = self._cell_roots.get(cell_name)
            if cell_root is None:
                raise KeyError(
                    'load label {} references an unknown cell named {}'
                    'known cells: {!r}'.format(label, cell_name, self._cell_roots))
            return BuildInclude(
                cell_name=cell_name,
                path=os.path.normpath(os.path.join(cell_root, relative_path, file_name)))
        else:
            return BuildInclude(
                cell_name=cell_name,
                path=os.path.normpath(os.path.join(self._project_root, relative_path, file_name)))

    def _read_config(self, section, field, default=None):
        # type: (str, str, Any) -> Any
        """
        Lookup a setting from `.buckconfig`.

        This method is meant to be installed into the globals of any files or
        includes that we process.
        """

        # Grab the current build context from the top of the stack.
        build_env = self._current_build_env

        # Lookup the value and record it in this build file's context.
        value = self._configs.get((section, field))
        build_env.used_configs[(section, field)] = value

        # If no config setting was found, return the default.
        if value is None:
            return default

        return value

    def _glob(self, includes, excludes=None, include_dotfiles=False, search_base=None,
              exclude=None):
        assert exclude is None or excludes is None, \
            "Mixing 'exclude' and 'excludes' attributes is not allowed. Please replace your " \
            "exclude and excludes arguments with a single 'excludes = %r'." % (exclude + excludes)
        excludes = excludes or exclude
        build_env = self._current_build_env
        return glob(
            includes, excludes=excludes, include_dotfiles=include_dotfiles,
            search_base=search_base, build_env=build_env,
            allow_safe_import=self._import_whitelist_manager.allow_unsafe_import)

    def _subdir_glob(self, glob_specs, excludes=None, prefix=None, search_base=None):
        build_env = self._current_build_env
        return subdir_glob(
            glob_specs, excludes=excludes, prefix=prefix, search_base=search_base,
            build_env=build_env,
            allow_safe_import=self._import_whitelist_manager.allow_unsafe_import)

    def _record_env_var(self, name, value):
        # type: (str, Any) -> None
        """
        Record a read of an environment variable.

        This method is meant to wrap methods in `os.environ` when called from
        any files or includes that we process.
        """

        # Grab the current build context from the top of the stack.
        build_env = self._current_build_env

        # Lookup the value and record it in this build file's context.
        build_env.used_env_vars[name] = value

    def _called_from_project_file(self):
        # type: () -> bool
        """
        Returns true if the function was called from a project file.
        """
        frame = get_caller_frame(skip=[__name__])
        filename = inspect.getframeinfo(frame).filename
        return is_in_dir(filename, self._project_root)

    def _include_defs(self, is_implicit_include, name, namespace=None):
        # type: (bool, str, Optional[str]) -> None
        """Pull the named include into the current caller's context.

        This method is meant to be installed into the globals of any files or
        includes that we process.
        """
        # Grab the current build context from the top of the stack.
        build_env = self._current_build_env

        # Resolve the named include to its path and process it to get its
        # build context and module.
        build_include = self._resolve_include(name)
        inner_env, mod = self._process_include(build_include, is_implicit_include)

        # Look up the caller's stack frame and merge the include's globals
        # into it's symbol table.
        frame = get_caller_frame(skip=['_functools', __name__])
        if namespace is not None:
            # If using a fresh namespace, create a fresh module to populate.
            fresh_module = imp.new_module(namespace)
            fresh_module.__file__ = mod.__file__
            self._merge_globals(mod, fresh_module.__dict__)
            frame.f_globals[namespace] = fresh_module
        else:
            self._merge_globals(mod, frame.f_globals)

        # Pull in the include's accounting of its own referenced includes
        # into the current build context.
        build_env.includes.add(build_include.path)
        build_env.merge(inner_env)

    def _load(self, is_implicit_include, name, *symbols, **symbol_kwargs):
        # type: (str, *str, **str) -> None
        """Pull the symbols from the named include into the current caller's context.

        This method is meant to be installed into the globals of any files or
        includes that we process.
        """
        # Grab the current build context from the top of the stack.
        build_env = self._current_build_env

        # Resolve the named include to its path and process it to get its
        # build context and module.
        build_include = self._get_load_path(name)
        inner_env, module = self._process_include(build_include, is_implicit_include)

        # Look up the caller's stack frame and merge the include's globals
        # into it's symbol table.
        frame = get_caller_frame(skip=['_functools', __name__])
        BuildFileProcessor._merge_explicit_globals(
            module, frame.f_globals, symbols, symbol_kwargs,
        )

        # Pull in the include's accounting of its own referenced includes
        # into the current build context.
        build_env.includes.add(build_include.path)
        build_env.merge(inner_env)

    def _provider(self):
        """Creates a declared provider factory.

        The return value of this function can be used to create "struct-like"
        values. Example:
            SomeInfo = provider()
            def foo():
              return 3
            info = SomeInfo(x = 2, foo = foo)
            print(info.x + info.foo())  # prints 5
        """
        return Struct


    def _add_build_file_dep(self, name):
        # type: (str) -> None
        """
        Explicitly specify a dependency on an external file.

        For instance, this can be used to specify a dependency on an external
        executable that will be invoked, or some other external configuration
        file.
        """

        # Grab the current build context from the top of the stack.
        build_env = self._current_build_env

        cell_name, path = self._resolve_include(name)
        build_env.includes.add(path)

    def _host_info(self):
        return _cached_host_info

    @contextlib.contextmanager
    def _set_build_env(self, build_env):
        """Set the given build context as the current context, unsetting it upon exit."""
        old_env = self._current_build_env
        self._current_build_env = build_env
        self._update_functions(self._current_build_env)
        try:
            yield
        finally:
            self._current_build_env = old_env
            self._update_functions(self._current_build_env)

    def _emit_warning(self, message, source):
        """
        Add a warning to the current build_env's diagnostics.
        """
        if self._current_build_env is not None:
            self._current_build_env.diagnostics.append(
                Diagnostic(
                    message=message,
                    level='warning',
                    source=source,
                    exception=None))

    @staticmethod
    def _create_import_whitelist(project_import_whitelist):
        """
        Creates import whitelist by joining the global whitelist with the project specific one
        defined in '.buckconfig'.
        """

        global_whitelist = ['copy', 're', 'functools', 'itertools', 'json', 'hashlib',
                            'types', 'string', 'ast', '__future__', 'collections',
                            'operator', 'fnmatch', 'copy_reg']

        return set(global_whitelist + project_import_whitelist)

    def _file_access_wrapper(self, real):
        """
        Return wrapper around function so that accessing a file produces warning if it is
        not a known dependency.
        """

        @functools.wraps(real)
        def wrapper(filename, *arg, **kwargs):
            # Restore original 'open' because it is used by 'inspect.currentframe()' in
            # '_called_from_project_file()'
            with self._wrap_file_access(wrap=False):
                if self._called_from_project_file():
                    path = os.path.abspath(filename)
                    if path not in self._current_build_env.includes:
                        dep_path = '//' + os.path.relpath(path, self._project_root)
                        warning_message = (
                            "Access to a non-tracked file detected! {0} is not a ".format(path) +
                            "known dependency and it should be added using 'add_build_file_dep' " +
                            "function before trying to access the file, e.g.\n" +
                            "'add_build_file_dep('{0}')'\n".format(dep_path) +
                            "The 'add_build_file_dep' function is documented at " +
                            "https://buckbuild.com/function/add_build_file_dep.html\n"
                        )
                        self._emit_warning(warning_message, 'sandboxing')

                return real(filename, *arg, **kwargs)

        # Save the real function for restoration.
        wrapper._real = real

        return wrapper

    @contextlib.contextmanager
    def _wrap_fun_for_file_access(self, obj, attr, wrap=True):
        """
        Wrap a function to check if accessed files are known dependencies.
        """
        real = getattr(obj, attr)
        if wrap:
            # Don't wrap again
            if not hasattr(real, "_real"):
                wrapped = self._file_access_wrapper(real)
                setattr(obj, attr, wrapped)
        elif hasattr(real, "_real"):
            # Restore real function if it was wrapped
            setattr(obj, attr, real._real)

        try:
            yield
        finally:
            setattr(obj, attr, real)

    def _wrap_file_access(self, wrap=True):
        """
        Wrap 'open' so that they it checks if accessed files are known dependencies.
        If 'wrap' is equal to False, restore original function instead.
        """
        return self._wrap_fun_for_file_access(__builtin__, 'open', wrap)

    @contextlib.contextmanager
    def _build_file_sandboxing(self):
        """
        Creates a context that sandboxes build file processing.
        """

        with self._wrap_file_access():
            with self._import_whitelist_manager.allow_unsafe_import(False):
                yield

    def _process(self, build_env, path, is_implicit_include):
        # type: (AbstractContext, str, bool) -> Tuple[AbstractContext, types.ModuleType]
        """Process a build file or include at the given path.

        :param build_env: context of the file to process.
        :param path: target-like path to the file to process.
        :param is_implicit_include: whether the file being processed is an implicit include, or was
            included from an implicit include.
        :returns: build context (potentially different if retrieved from cache) and loaded module.
        """

        # Install the build context for this input as the current context.
        with self._set_build_env(build_env):
            # Set of helpers callable from the child environment.
            default_globals = {
                'include_defs': functools.partial(self._include_defs, is_implicit_include),
                'add_build_file_dep': self._add_build_file_dep,
                'read_config': self._read_config,
                'allow_unsafe_import': self._import_whitelist_manager.allow_unsafe_import,
                'glob': self._glob,
                'subdir_glob': self._subdir_glob,
                'load': functools.partial(self._load, is_implicit_include),
                'struct': Struct,
                'provider': self._provider,
                'host_info': self._host_info,
                'native': self._create_native_module(),
            }

            # Don't include implicit includes if the current file being
            # processed is an implicit include
            if not is_implicit_include:
                for include in self._implicit_includes:
                    build_include = self._resolve_include(include)
                    inner_env, mod = self._process_include(build_include, True)
                    self._merge_globals(mod, default_globals)
                    build_env.includes.add(build_include.path)
                    build_env.merge(inner_env)

            # Build a new module for the given file, using the default globals
            # created above.
            module = imp.new_module(path)
            module.__file__ = path
            module.__dict__.update(default_globals)

            # We don't open this file as binary, as we assume it's a textual source
            # file.
            with self._wrap_file_access(wrap=False):
                with open(path, 'r') as f:
                    contents = f.read()

            # Enable absolute imports.  This prevents the compiler from trying to
            # do a relative import first, and warning that this module doesn't
            # exist in sys.modules.
            future_features = __future__.absolute_import.compiler_flag
            code = compile(contents, path, 'exec', future_features, 1)

            # Execute code with build file sandboxing
            with self._build_file_sandboxing():
                exec(code, module.__dict__)

        return build_env, module

    def _process_include(self, build_include, is_implicit_include):
        # type: (BuildInclude, bool) -> Tuple[AbstractContext, types.ModuleType]
        """Process the include file at the given path.

        :param build_include: build include metadata (cell_name and path).
        :param is_implicit_include: whether the file being processed is an implicit include, or was
            included from an implicit include.
        """

        # First check the cache.
        cached = self._include_cache.get(build_include.path)
        if cached is not None:
            return cached

        build_env = IncludeContext(cell_name=build_include.cell_name, path=build_include.path)
        build_env, mod = self._process(build_env, build_include.path,
                                       is_implicit_include=is_implicit_include)

        self._include_cache[build_include.path] = build_env, mod
        return build_env, mod

    def _process_build_file(self, watch_root, project_prefix, path):
        """Process the build file at the given path."""
        # Create the build file context, including the base path and directory
        # name of the given path.
        relative_path_to_build_file = os.path.relpath(path, self._project_root).replace('\\', '/')
        len_suffix = -len('/' + self._build_file_name)
        base_path = relative_path_to_build_file[:len_suffix]
        dirname = os.path.dirname(path)

        build_env = BuildFileContext(
            self._project_root,
            base_path,
            path,
            dirname,
            self._cell_name,
            self._allow_empty_globs,
            self._ignore_paths,
            self._watchman_client,
            watch_root,
            project_prefix,
            self._sync_cookie_state,
            self._watchman_glob_stat_results,
            self._watchman_use_glob_generator)

        return self._process(build_env, path, is_implicit_include=False)

    def process(self, watch_root, project_prefix, path, diagnostics):
        # type: (str, Optional[str], str, List[Diagnostic]) -> List[Dict[str, Any]]
        """Process a build file returning a dict of its rules and includes."""
        build_env, mod = self._process_build_file(watch_root, project_prefix,
                                                  os.path.join(self._project_root, path))

        # Initialize the output object to a map of the parsed rules.
        values = build_env.rules.values()

        # Add in tracked included files as a special meta rule.
        values.append({"__includes": [path] + sorted(build_env.includes)})

        # Add in tracked used config settings as a special meta rule.
        configs = {}
        for (section, field), value in build_env.used_configs.iteritems():
            configs.setdefault(section, {})
            configs[section][field] = value
        values.append({"__configs": configs})

        # Add in used environment variables as a special meta rule.
        values.append({"__env": build_env.used_env_vars})

        diagnostics.extend(build_env.diagnostics)

        return values


class InvalidSignatureError(Exception):
    pass


def format_traceback(tb):
    formatted = []
    for entry in traceback.extract_tb(tb):
        (filename, line_number, function_name, text) = entry
        formatted.append({
            'filename': filename,
            'line_number': line_number,
            'function_name': function_name,
            'text': text,
        })
    return formatted


def format_exception_info(exception_info):
    (exc_type, exc_value, exc_traceback) = exception_info
    formatted = {
        'type': exc_type.__name__,
        'value': str(exc_value),
        'traceback': format_traceback(exc_traceback),
    }
    if exc_type is SyntaxError:
        formatted['filename'] = exc_value.filename
        formatted['lineno'] = exc_value.lineno
        formatted['offset'] = exc_value.offset
        formatted['text'] = exc_value.text
    return formatted


def encode_result(values, diagnostics, profile):
    result = {'values': [
        dict((k, v) for k, v in value.iteritems() if v is not None)
        for value in values]}
    json_encoder = BuckJSONEncoder()
    if diagnostics:
        encoded_diagnostics = []
        for d in diagnostics:
            encoded = {
                'message': d.message,
                'level': d.level,
                'source': d.source,
            }
            if d.exception:
                encoded['exception'] = format_exception_info(d.exception)
            encoded_diagnostics.append(encoded)
        result['diagnostics'] = encoded_diagnostics
    if profile is not None:
        result['profile'] = profile
    try:
        return json_encoder.encode(result)
    except Exception as e:
        # Try again without the values
        result['values'] = []
        if 'diagnostics' not in result:
            result['diagnostics'] = []
        result['diagnostics'].append({
            'message': str(e),
            'level': 'fatal',
            'source': 'parse',
            'exception': format_exception_info(sys.exc_info()),
        })
        return json_encoder.encode(result)


def process_with_diagnostics(build_file_query, build_file_processor, to_parent,
                             should_profile=False):
    start_time = time.time()
    build_file = build_file_query.get('buildFile')
    watch_root = build_file_query.get('watchRoot')
    project_prefix = build_file_query.get('projectPrefix')

    build_file = cygwin_adjusted_path(build_file).rstrip().encode('ascii')
    watch_root = cygwin_adjusted_path(watch_root).rstrip().encode('ascii')
    if project_prefix is not None:
        project_prefix = cygwin_adjusted_path(project_prefix).rstrip().encode('ascii')

    diagnostics = []
    values = []
    try:
        values = build_file_processor.process(
            watch_root,
            project_prefix,
            build_file,
            diagnostics=diagnostics)
    except Exception as e:
        # Control-C and sys.exit() don't emit diagnostics.
        if not (e is KeyboardInterrupt or e is SystemExit):
            if isinstance(e, WatchmanError):
                source = 'watchman'
                message = e.msg
            else:
                source = 'parse'
                message = str(e)
            diagnostics.append(
                Diagnostic(
                    message=message,
                    level='fatal',
                    source=source,
                    exception=sys.exc_info()))
        raise
    finally:
        java_process_send_result(to_parent, values, diagnostics, None)

    end_time = time.time()
    return end_time - start_time


def java_process_send_result(to_parent, values, diagnostics, profile_result):
    """Sends result to the Java process"""
    data = encode_result(values, diagnostics, profile_result)
    to_parent.write(data)
    to_parent.flush()


def silent_excepthook(exctype, value, tb):
    # We already handle all exceptions by writing them to the parent, so
    # no need to dump them again to stderr.
    pass


def _optparse_store_kv(option, opt_str, value, parser):
    """Optparse option callback which parses input as K=V, and store into dictionary.

    :param optparse.Option option: Option instance
    :param str opt_str: string representation of option flag
    :param str value: argument value
    :param optparse.OptionParser parser: parser instance
    """
    result = value.split('=', 1)
    if len(result) != 2:
        raise optparse.OptionError(
            "Expected argument of to be in the form of X=Y".format(opt_str))
    (k, v) = result

    # Get or create the dictionary
    dest_dict = getattr(parser.values, option.dest)
    if dest_dict is None:
        dest_dict = {}
        setattr(parser.values, option.dest, dest_dict)

    dest_dict[k] = v


# Inexplicably, this script appears to run faster when the arguments passed
# into it are absolute paths. However, we want the "buck.base_path" property
# of each rule to be printed out to be the base path of the build target that
# identifies the rule. That means that when parsing a BUCK file, we must know
# its path relative to the root of the project to produce the base path.
#
# To that end, the first argument to this script must be an absolute path to
# the project root.  It must be followed by one or more absolute paths to
# BUCK files under the project root.  If no paths to BUCK files are
# specified, then it will traverse the project root for BUCK files, excluding
# directories of generated files produced by Buck.
#
# All of the build rules that are parsed from the BUCK files will be printed
# to stdout encoded in JSON. That means that printing out other information
# for debugging purposes will break the JSON encoding, so be careful!


def main():
    # Our parent expects to read JSON from our stdout, so if anyone
    # uses print, buck will complain with a helpful "but I wanted an
    # array!" message and quit.  Redirect stdout to stderr so that
    # doesn't happen.  Actually dup2 the file handle so that writing
    # to file descriptor 1, os.system, and so on work as expected too.

    to_parent = os.fdopen(os.dup(sys.stdout.fileno()), 'ab')
    os.dup2(sys.stderr.fileno(), sys.stdout.fileno())

    parser = optparse.OptionParser()
    parser.add_option(
        '--project_root',
        action='store',
        type='string',
        dest='project_root')
    parser.add_option(
        '--cell_root',
        action='callback',
        type='string',
        dest='cell_roots',
        metavar='NAME=PATH',
        help='Cell roots that can be referenced by includes.',
        callback=_optparse_store_kv,
        default={},
    )
    parser.add_option(
        '--cell_name',
        action='store',
        type='string',
        dest="cell_name")
    parser.add_option(
        '--build_file_name',
        action='store',
        type='string',
        dest="build_file_name")
    parser.add_option(
        '--allow_empty_globs',
        action='store_true',
        dest='allow_empty_globs',
        help='Tells the parser not to raise an error when glob returns no results.')
    parser.add_option(
        '--use_watchman_glob',
        action='store_true',
        dest='use_watchman_glob',
        help='Invokes `watchman query` to get lists of files instead of globbing in-process.')
    parser.add_option(
        '--watchman_use_glob_generator',
        action='store_true',
        dest='watchman_use_glob_generator',
        help='Uses Watchman glob generator to speed queries')
    parser.add_option(
        '--watchman_glob_stat_results',
        action='store_true',
        dest='watchman_glob_stat_results',
        help='Invokes `stat()` to sanity check result of `watchman query`.')
    parser.add_option(
        '--watchman_socket_path',
        action='store',
        type='string',
        dest='watchman_socket_path',
        help='Path to Unix domain socket/named pipe as returned by `watchman get-sockname`.')
    parser.add_option(
        '--watchman_query_timeout_ms',
        action='store',
        type='int',
        dest='watchman_query_timeout_ms',
        help='Maximum time in milliseconds to wait for watchman query to respond.')
    parser.add_option(
        '--include',
        action='append',
        dest='include')
    parser.add_option(
        '--config',
        help='BuckConfig settings available at parse time.')
    parser.add_option(
        '--ignore_paths',
        help='Paths that should be ignored.')
    parser.add_option(
        '--quiet',
        action='store_true',
        dest='quiet',
        help='Stifles exception backtraces printed to stderr during parsing.')
    parser.add_option(
        '--profile',
        action='store_true',
        help='Profile every buck file execution')
    parser.add_option(
        '--build_file_import_whitelist',
        action='append',
        dest='build_file_import_whitelist')
    parser.add_option(
        '--disable_implicit_native_rules',
        action='store_true',
        help='Do not allow native rules in build files, only included ones',
    )
    parser.add_option(
        '--warn_about_deprecated_syntax',
        action='store_true',
        help='Warn about deprecated syntax usage.',
    )
    (options, args) = parser.parse_args()

    # Even though project_root is absolute path, it may not be concise. For
    # example, it might be like "C:\project\.\rule".
    #
    # Under cygwin, the project root will be invoked from buck as C:\path, but
    # the cygwin python uses UNIX-style paths. They can be converted using
    # cygpath, which is necessary because abspath will treat C:\path as a
    # relative path.
    options.project_root = cygwin_adjusted_path(options.project_root)
    project_root = os.path.abspath(options.project_root)
    cell_roots = dict((k, os.path.abspath(cygwin_adjusted_path(v)))
                      for (k, v) in options.cell_roots.iteritems())

    watchman_client = None
    if options.use_watchman_glob:
        client_args = {'sendEncoding': 'json', 'recvEncoding': 'json'}
        if options.watchman_query_timeout_ms is not None:
            # pywatchman expects a timeout as a nonnegative floating-point
            # value in seconds.
            client_args['timeout'] = max(0.0, options.watchman_query_timeout_ms / 1000.0)
        else:
            client_args['timeout'] = DEFAULT_WATCHMAN_QUERY_TIMEOUT
        if options.watchman_socket_path is not None:
            client_args['sockpath'] = options.watchman_socket_path
            client_args['transport'] = 'local'
        watchman_client = pywatchman.client(**client_args)

    configs = {}
    if options.config is not None:
        with open(options.config, 'rb') as f:
            for section, contents in json.load(f).iteritems():
                for field, value in contents.iteritems():
                    configs[(section, field)] = value

    ignore_paths = []
    if options.ignore_paths is not None:
        with open(options.ignore_paths, 'rb') as f:
            ignore_paths = [make_glob(i) for i in json.load(f)]

    buildFileProcessor = BuildFileProcessor(
        project_root,
        cell_roots,
        options.cell_name,
        options.build_file_name,
        options.allow_empty_globs,
        watchman_client,
        options.watchman_glob_stat_results,
        options.watchman_use_glob_generator,
        project_import_whitelist=options.build_file_import_whitelist or [],
        implicit_includes=options.include or [],
        configs=configs,
        ignore_paths=ignore_paths,
        disable_implicit_native_rules=options.disable_implicit_native_rules,
        warn_about_deprecated_syntax=options.warn_about_deprecated_syntax)

    # While processing, we'll write exceptions as diagnostic messages
    # to the parent then re-raise them to crash the process. While
    # doing so, we don't want Python's default unhandled exception
    # behavior of writing to stderr.
    orig_excepthook = None
    if options.quiet:
        orig_excepthook = sys.excepthook
        sys.excepthook = silent_excepthook

    # Process the build files with the env var interceptors and builtins
    # installed.
    with buildFileProcessor.with_env_interceptors():
        with buildFileProcessor.with_builtins(__builtin__.__dict__):
            processed_build_file = []

            profiler = None
            if options.profile:
                profiler = Profiler(True)
                profiler.start()

            for build_file in args:
                query = {
                    'buildFile': build_file,
                    'watchRoot': project_root,
                    'projectPrefix': project_root,
                }
                duration = process_with_diagnostics(
                    query,
                    buildFileProcessor,
                    to_parent,
                    should_profile=options.profile)
                processed_build_file.append({'buildFile': build_file, 'duration': duration})

            # From https://docs.python.org/2/using/cmdline.html :
            #
            # Note that there is internal buffering in file.readlines()
            # and File Objects (for line in sys.stdin) which is not
            # influenced by this option. To work around this, you will
            # want to use file.readline() inside a while 1: loop.
            for line in wait_and_read_build_file_query():
                if line == '':
                    break
                build_file_query = json.loads(line)
                if build_file_query.get('command') == 'report_profile':
                    report_profile(options, to_parent, processed_build_file, profiler)
                else:
                    duration = process_with_diagnostics(
                        build_file_query,
                        buildFileProcessor,
                        to_parent,
                        should_profile=options.profile)
                    processed_build_file.append(
                        {'buildFile': build_file_query['buildFile'], 'duration': duration})

    if options.quiet:
        sys.excepthook = orig_excepthook

    # Python tries to flush/close stdout when it quits, and if there's a dead
    # pipe on the other end, it will spit some warnings to stderr. This breaks
    # tests sometimes. Prevent that by explicitly catching the error.
    try:
        to_parent.close()
    except IOError:
        pass


def wait_build_file_query():
    select.select([sys.stdin], [], [])


def wait_and_read_build_file_query():
    def default_wait():
        return
    wait = default_wait
    if sys.platform != 'win32':
        # wait_build_file_query() is useful to attribute time waiting for queries.
        # Since select.select() is not supported on Windows, we currently don't have
        # a reliable way to measure it on this platform. Then, we skip it.
        wait = wait_build_file_query
    while True:
        wait()
        line = sys.stdin.readline()
        if not line:
            return
        yield line


def report_profile(options, to_parent, processed_build_file, profiler):
    if options.profile:
        try:
            profiler.stop()
            profile_result = profiler.generate_report()
            extra_result = "Total: {:.2f} sec\n\n\n".format(profiler.total_time)
            extra_result += '# Parsed {} files'.format(len(processed_build_file))
            processed_build_file.sort(
                key=lambda current_child: current_child['duration'], reverse=True)
            # Only show the top ten buck files
            if len(processed_build_file) > 10:
                processed_build_file = processed_build_file[:10]
                extra_result += ', {} slower BUCK files:\n'.format(len(processed_build_file))
            else:
                extra_result += '\n'
            for info in processed_build_file:
                extra_result += 'Parsed {}: {:.2f} sec \n'.format(
                    info['buildFile'], info['duration'])
            extra_result += '\n\n'
            profile_result = extra_result + profile_result
            java_process_send_result(to_parent, [], [], profile_result)
        except Exception as e:
            trace = traceback.format_exc()
            print(str(trace))
            raise
    else:
        to_parent.write(encode_result([], [], None))
        to_parent.flush()


def make_glob(pat):
    if is_special(pat):
        return pat
    return pat + '/**'


# import autogenerated rule instances for effect.
try:
    import generated_rules
except ImportError:
    # If running directly or python tests of this code, this is not an error.
    sys.stderr.write('Failed to load buck generated rules module.\n')
