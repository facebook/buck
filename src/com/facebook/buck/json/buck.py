
from __future__ import with_statement

import __builtin__
import __future__

import contextlib
from collections import namedtuple
from pathlib import Path, PureWindowsPath, PurePath
from pywatchman import bser
from contextlib import contextmanager
import copy
import StringIO
import cProfile
import functools
import hashlib
import imp
import inspect
import json
import optparse
import os
import os.path
import pstats
import re
import subprocess
import sys
import traceback
import types

# When build files are executed, the functions in this file tagged with
# @provide_for_build will be provided in the build file's local symbol table.
#
# When these functions are called from a build file, they will be passed
# a keyword parameter, build_env, which is a object with information about
# the environment of the build file which is currently being processed.
# It contains the following attributes:
#
# "dirname" - The directory containing the build file.
#
# "base_path" - The base path of the build file.

BUILD_FUNCTIONS = []

VERIFY_AUTODEPS_SIGNATURE = False

# Wait this many seconds on recv() or send() in the pywatchman client
# if not otherwise specified in .buckconfig
DEFAULT_WATCHMAN_QUERY_TIMEOUT = 5.0

ORIGINAL_IMPORT = __builtin__.__import__

class SyncCookieState(object):
    """
    Process-wide state used to enable Watchman sync cookies only on
    the first query issued.
    """

    def __init__(self):
        self.use_sync_cookies = True


class BuildContextType(object):

    """
    Identifies the type of input file to the processor.
    """

    BUILD_FILE = 'build_file'
    INCLUDE = 'include'


class BuildFileContext(object):
    """
    The build context used when processing a build file.
    """

    type = BuildContextType.BUILD_FILE

    def __init__(self, project_root, base_path, dirname, autodeps, allow_empty_globs, ignore_paths,
                 watchman_client, watchman_watch_root, watchman_project_prefix,
                 sync_cookie_state, watchman_error, watchman_glob_stat_results,
                 watchman_use_glob_generator):
        self.globals = {}
        self.includes = set()
        self.used_configs = {}
        self.used_env_vars = {}
        self.project_root = project_root
        self.base_path = base_path
        self.dirname = dirname
        self.autodeps = autodeps
        self.allow_empty_globs = allow_empty_globs
        self.ignore_paths = ignore_paths
        self.watchman_client = watchman_client
        self.watchman_watch_root = watchman_watch_root
        self.watchman_project_prefix = watchman_project_prefix
        self.sync_cookie_state = sync_cookie_state
        self.watchman_error = watchman_error
        self.watchman_glob_stat_results = watchman_glob_stat_results
        self.watchman_use_glob_generator = watchman_use_glob_generator
        self.diagnostics = set()
        self.rules = {}


class IncludeContext(object):
    """
    The build context used when processing an include.
    """

    type = BuildContextType.INCLUDE

    def __init__(self):
        self.globals = {}
        self.includes = set()
        self.used_configs = {}
        self.used_env_vars = {}
        self.diagnostics = set()


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
        return self.func(*args, **updated_kwargs)


Diagnostic = namedtuple('Diagnostic', ['message', 'level', 'source'])


def provide_for_build(func):
    BUILD_FUNCTIONS.append(func)
    return func


def add_rule(rule, build_env):
    assert build_env.type == BuildContextType.BUILD_FILE, (
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

    # It is possible that the user changed the rule from autodeps=True to autodeps=False
    # without re-running `buck autodeps` (this is common when resolving merge conflicts).
    # When this happens, the deps in BUCK.autodeps should be ignored because autodeps is
    # set to False.
    if rule_name in build_env.autodeps:
        if rule.get('autodeps', False):
            # TODO(bolinfest): One major edge case that exists right now when using a set to de-dupe
            # elements is that the same target may be referenced in two different ways:
            # 1. As a fully-qualified target: //src/com/facebook/buck/android:packageable
            # 2. As a local target:           :packageable
            # Because of this, we may end up with two entries for the same target even though we
            # are trying to use a set to remove duplicates.

            # Combine all of the deps into a set to eliminate duplicates. Although we would prefer
            # it if each dep were exclusively in BUCK or BUCK.autodeps, that is not always
            # possible. For example, if a user-defined macro creates a library that hardcodes a dep
            # and the tooling to produce BUCK.autodeps also infers the need for that dep and adds
            # it to BUCK.autodeps, then it will appear in both places.
            explicit_deps = rule.get('deps', [])
            auto_deps = build_env.autodeps[rule_name]['deps']
            deps = set(explicit_deps)
            deps.update(auto_deps)
            rule['deps'] = list(deps)

            explicit_exported_deps = rule.get('exportedDeps', [])
            auto_exported_deps = build_env.autodeps[rule_name]['exported_deps']
            exported_deps = set(explicit_exported_deps)
            exported_deps.update(auto_exported_deps)
            rule['exportedDeps'] = list(exported_deps)
        else:
            # If there is an entry in the .autodeps file for the rule, but the rule has autodeps
            # set to False, then the .autodeps file is likely out of date. Ideally, we would warn
            # the user to re-run `buck autodeps` in this scenario. Unfortunately, we do not have
            # a mechanism to relay warnings from buck.py at the time of this writing.
            pass
    build_env.rules[rule_name] = rule


def memoized(deepcopy=True):
    '''Decorator. Caches a function's return value each time it is called.
    If called later with the same arguments, the cached value is returned
    (not reevaluated).

    Makes a defensive copy of the cached value each time it's returned,
    so callers mutating the result do not poison the cache, unless
    deepcopy is set to False.

    '''
    def decorator(func):
        cache = {}

        @functools.wraps(func)
        def wrapped(*args, **kwargs):
            # poor-man's cache key; the keyword args are sorted to avoid dictionary ordering
            # issues (insertion and deletion order matters). Nested dictionaries will still cause
            # cache misses.
            args_key = repr(args) + repr(sorted(kwargs.items()))
            _sentinel = object()
            value = cache.get(args_key, _sentinel)
            if value is _sentinel:
                value = func(*args, **kwargs)
                cache[args_key] = value
            # Return a copy to ensure callers mutating the result don't poison the cache.
            if deepcopy:
                value = copy.deepcopy(value)
            return value

        return wrapped

    return decorator


@provide_for_build
def glob(includes, excludes=None, include_dotfiles=False, build_env=None, search_base=None):
    if excludes is None:
        excludes = []
    assert build_env.type == BuildContextType.BUILD_FILE, (
        "Cannot use `glob()` at the top-level of an included file.")
    # Ensure the user passes lists of strings rather than just a string.
    assert not isinstance(includes, basestring), \
        "The first argument to glob() must be a list of strings."
    assert not isinstance(excludes, basestring), \
        "The excludes argument must be a list of strings."

    results = None
    if not includes:
        results = []
    elif build_env.watchman_client:
        try:
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
        except build_env.watchman_error as e:
            build_env.diagnostics.add(
                Diagnostic(
                    message=str(e),
                    level='error',
                    source='watchman'))
            try:
                build_env.watchman_client.close()
            except:
                pass
            build_env.watchman_client = None

    if results is None:
        if search_base is None:
            search_base = Path(build_env.dirname)

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
                       search_base=None):
    if excludes is None:
        excludes = []
    results = {}
    files = glob([os.path.join(dirpath, glob_pattern)],
                 excludes=excludes,
                 build_env=build_env,
                 search_base=search_base)
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


@provide_for_build
def subdir_glob(glob_specs, excludes=None, prefix=None, build_env=None, search_base=None):
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
            single_subdir_glob(dirpath, glob_pattern, excludes, prefix, build_env, search_base))

    return merge_maps(*results)


COLLAPSE_SLASHES = re.compile(r'/+')


def format_watchman_query_params(includes, excludes, include_dotfiles, relative_root,
                                 watchman_use_glob_generator):
    match_exprs = ["allof", ["anyof", ["type", "f"], ["type", "l"]]]
    match_flags = {}

    if include_dotfiles:
        match_flags["includedotfiles"] = True
    if excludes:
        match_exprs.append(
            ["not",
             ["anyof"] +
             [["match", COLLAPSE_SLASHES.sub('/', x), "wholename", match_flags]
              for x in excludes]])

    query = {
        "relative_root": relative_root,
        "fields": ["name"],
    }

    if watchman_use_glob_generator:
        query["glob"] = includes
        # We don't have to check for existence; the glob generator only matches
        # files which exist.
    else:
        # We do have to check for existence; the path generator
        # includes files which don't exist.
        match_exprs.append("exists")

        # Explicitly pass an empty path so Watchman queries only the tree of files
        # starting at base_path.
        query["path"] = ['']
        match_exprs.append(
            ["anyof"] +
            # Collapse multiple consecutive slashes in pattern until fix in
            # https://github.com/facebook/watchman/pull/310/ is available
            [["match", COLLAPSE_SLASHES.sub('/', i), "wholename", match_flags] for i in includes])

    query["expression"] = match_exprs

    return query


@memoized()
def glob_watchman(includes, excludes, include_dotfiles, base_path, watchman_watch_root,
                  watchman_project_prefix, sync_cookie_state, watchman_client, diagnostics,
                  watchman_glob_stat_results, watchman_use_glob_generator):
    assert includes, "The includes argument must be a non-empty list of strings."

    if watchman_project_prefix:
        relative_root = os.path.join(watchman_project_prefix, base_path)
    else:
        relative_root = base_path
    query_params = format_watchman_query_params(
        includes, excludes, include_dotfiles, relative_root, watchman_use_glob_generator)

    # Sync cookies cause a massive overhead when issuing thousands of
    # glob queries.  Only enable them (by not setting sync_timeout to 0)
    # for the very first request issued by this process.
    if sync_cookie_state.use_sync_cookies:
        sync_cookie_state.use_sync_cookies = False
    else:
        query_params["sync_timeout"] = 0

    query = ["query", watchman_watch_root, query_params]
    res = watchman_client.query(*query)
    error_message = res.get('error')
    if error_message is not None:
        diagnostics.add(
            Diagnostic(
                message=error_message,
                level='error',
                source='watchman'))
    warning_message = res.get('warning')
    if warning_message is not None:
        diagnostics.add(
            Diagnostic(
                message=warning_message,
                level='warning',
                source='watchman'))
    result = res.get('files', [])
    if watchman_glob_stat_results:
        result = stat_results(base_path, result, diagnostics)
    return sorted(result)


def stat_results(base_path, result, diagnostics):
    statted_result = []
    for path in result:
        # We really shouldn't have to stat() every result from Watchman, but
        # occasionally it returns non-existent files.
        resolved_path = os.path.join(base_path, path)
        if os.path.exists(resolved_path):
            statted_result.append(path)
        else:
            diagnostics.add(
                Diagnostic(
                    message='Watchman query returned non-existent file: {0}'.format(
                        resolved_path),
                    level='warning',
                    source='watchman'))
    return statted_result


def path_component_contains_dot(relative_path):
    for p in relative_path.parts:
        if p.startswith('.'):
            return True
    return False


def glob_internal(includes, excludes, project_root_relative_excludes, include_dotfiles, search_base, project_root):

    def includes_iterator():
        for pattern in includes:
            for path in search_base.glob(pattern):
                # TODO(bhamiltoncx): Handle hidden files on Windows.
                if path.is_file() and \
                   (include_dotfiles or not path_component_contains_dot(
                       path.relative_to(search_base))):
                    yield path

    non_special_excludes = set()
    match_excludes = set()
    for pattern in excludes:
        if is_special(pattern):
            match_excludes.add(pattern)
        else:
            non_special_excludes.add(pattern)

    def exclusion(path):
        relative_to_search_base = path.relative_to(search_base)
        if relative_to_search_base.as_posix() in non_special_excludes:
            return True
        for pattern in match_excludes:
            result = relative_to_search_base.match(pattern, match_entire=True)
            if result:
                return True
        relative_to_project_root = path.relative_to(project_root)
        for pattern in project_root_relative_excludes:
            result = relative_to_project_root.match(pattern, match_entire=True)
            if result:
                return True
        return False

    return sorted(set([
        str(p.relative_to(search_base)) for p in includes_iterator() if not exclusion(p)]))


@provide_for_build
def get_base_path(build_env=None):
    """Get the base path to the build file that was initially evaluated.

    This function is intended to be used from within a build defs file that
    likely contains macros that could be called from any build file.
    Such macros may need to know the base path of the file in which they
    are defining new build rules.

    Returns: a string, such as "java/com/facebook". Note there is no
             trailing slash. The return value will be "" if called from
             the build file in the root of the project.
    """
    assert build_env.type == BuildContextType.BUILD_FILE, (
        "Cannot use `get_base_path()` at the top-level of an included file.")
    return build_env.base_path


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


GENDEPS_SIGNATURE = re.compile(r'^#@# GENERATED FILE: DO NOT MODIFY ([a-f0-9]{40}) #@#\n$')


class BuildFileProcessor(object):

    def __init__(self, project_root, watchman_watch_root, watchman_project_prefix, build_file_name,
                 allow_empty_globs, ignore_buck_autodeps_files, watchman_client, watchman_error,
                 watchman_glob_stat_results, watchman_use_glob_generator,
                 enable_build_file_sandboxing,
                 project_import_whitelist=None, implicit_includes=None, extra_funcs=None,
                 configs=None, env_vars=None, ignore_paths=None):
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
        self._cache = {}
        self._build_env_stack = []
        self._sync_cookie_state = SyncCookieState()

        self._project_root = project_root
        self._watchman_watch_root = watchman_watch_root
        self._watchman_project_prefix = watchman_project_prefix
        self._build_file_name = build_file_name
        self._implicit_includes = implicit_includes
        self._allow_empty_globs = allow_empty_globs
        self._ignore_buck_autodeps_files = ignore_buck_autodeps_files
        self._enable_build_file_sandboxing = enable_build_file_sandboxing
        self._watchman_client = watchman_client
        self._watchman_error = watchman_error
        self._watchman_glob_stat_results = watchman_glob_stat_results
        self._watchman_use_glob_generator = watchman_use_glob_generator
        self._configs = configs
        self._env_vars = env_vars
        self._ignore_paths = ignore_paths

        lazy_functions = {}
        for func in BUILD_FUNCTIONS + extra_funcs:
            func_with_env = LazyBuildEnvPartial(func)
            lazy_functions[func.__name__] = func_with_env
        self._functions = lazy_functions
        self._safe_modules_config = self._create_safe_modules_config()
        self._safe_modules = {}
        self._custom_import = self._create_custom_import()
        self._import_whitelist = self._create_import_whitelist(project_import_whitelist)

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
    def _with_env_interceptor(self, read, obj, attr):
        """
        Wrap a function, found at `obj.attr`, that reads an environment
        variable in a new function which records the env var read.
        """

        real = getattr(obj, attr)
        wrapped = self._wrap_env_var_read(read, real)
        setattr(obj, attr, wrapped)
        try:
            yield
        finally:
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
        with contextlib.nested(
                self._with_env_interceptor(read, os.environ, '__contains__'),
                self._with_env_interceptor(read, os.environ, '__getitem__'),
                self._with_env_interceptor(read, os.environ, 'get')):
            yield

    def _merge_globals(self, mod, dst):
        """
        Copy the global definitions from one globals dict to another.

        Ignores special attributes and attributes starting with '_', which
        typically denote module-level private attributes.
        """

        hidden = set([
            'include_defs',
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

        for function in self._functions.itervalues():
            function.build_env = build_env

    def install_builtins(self, namespace):
        """
        Installs the build functions, by their name, into the given namespace.
        """

        for name, function in self._functions.iteritems():
            namespace[name] = function.invoke

    def _get_include_path(self, name):
        """
        Resolve the given include def name to a full path.
        """

        # Find the path from the include def name.
        if not name.startswith('//'):
            raise ValueError(
                'include_defs argument "%s" must begin with //' % name)
        relative_path = name[2:]
        return os.path.join(self._project_root, relative_path)

    def _read_config(self, section, field, default=None):
        """
        Lookup a setting from `.buckconfig`.

        This method is meant to be installed into the globals of any files or
        includes that we process.
        """

        # Grab the current build context from the top of the stack.
        build_env = self._build_env_stack[-1]

        # Lookup the value and record it in this build file's context.
        value = self._configs.get((section, field))
        build_env.used_configs[(section, field)] = value

        # If no config setting was found, return the default.
        if value is None:
            return default

        return value

    def _record_env_var(self, name, value):
        """
        Record a read of an environment variable.

        This method is meant to wrap methods in `os.environ` when called from
        any files or includes that we process.
        """

        # Grab the current build context from the top of the stack.
        build_env = self._build_env_stack[-1]

        # Lookup the value and record it in this build file's context.
        build_env.used_env_vars[name] = value

    def _include_defs(self, name, implicit_includes=None):
        """
        Pull the named include into the current caller's context.

        This method is meant to be installed into the globals of any files or
        includes that we process.
        """
        if implicit_includes is None:
            implicit_includes = []

        # Grab the current build context from the top of the stack.
        build_env = self._build_env_stack[-1]

        # Resolve the named include to its path and process it to get its
        # build context and module.
        path = self._get_include_path(name)
        inner_env, mod = self._process_include(
            path,
            implicit_includes=implicit_includes)

        # Look up the caller's stack frame and merge the include's globals
        # into it's symbol table.
        frame = inspect.currentframe()
        while frame.f_globals['__name__'] in (__name__, '_functools'):
            frame = frame.f_back
        self._merge_globals(mod, frame.f_globals)

        # Pull in the include's accounting of its own referenced includes
        # into the current build context.
        build_env.includes.add(path)
        build_env.includes.update(inner_env.includes)

        # Pull in any diagnostics issued by the include.
        build_env.diagnostics.update(inner_env.diagnostics)

        # Pull in any config settings used by the include.
        build_env.used_configs.update(inner_env.used_configs)

        # Pull in any config settings used by the include.
        build_env.used_env_vars.update(inner_env.used_env_vars)

    def _add_build_file_dep(self, name):
        """
        Explicitly specify a dependency on an external file.

        For instance, this can be used to specify a dependency on an external
        executable that will be invoked, or some other external configuration
        file.
        """

        # Grab the current build context from the top of the stack.
        build_env = self._build_env_stack[-1]

        path = self._get_include_path(name)
        build_env.includes.add(path)

    def _push_build_env(self, build_env):
        """
        Set the given build context as the current context.
        """

        self._build_env_stack.append(build_env)
        self._update_functions(build_env)

    def _pop_build_env(self):
        """
        Restore the previous build context as the current context.
        """

        self._build_env_stack.pop()
        if self._build_env_stack:
            self._update_functions(self._build_env_stack[-1])

    def _block_unsafe_function(self, module, name):
        # Returns a function that ignores any arguments and raises AttributeError.
        def func(*args, **kwargs):
            raise AttributeError(
                'Using function %s is forbidden in the safe version of ' % name +
                'module %s. If you really need to use this function read about ' % module +
                'allow_unsafe_import() that is documented at ' +
                'https://buckbuild.com/function/allow_unsafe_import.html'
            )

        return func

    def _install_whitelisted_parts(self, mod, safe_mod, whitelist):
        """
        Copy whitelisted globals from a module to its safe version.
        Functions not on the whitelist are blocked to show a more meaningful error.
        """

        mod_name = safe_mod.__name__
        whitelist_set = set(whitelist)
        for name in mod.__dict__:
            if name in whitelist_set:
                # Check if a safe version is defined in case it's a submodule.
                # If it's not defined the original submodule will be copied.
                submodule_name = mod_name + '.' + name
                if submodule_name in self._safe_modules_config:
                    # Get a safe version of the submodule
                    safe_mod.__dict__[name] = self._get_safe_module(submodule_name)
                else:
                    safe_mod.__dict__[name] = mod.__dict__[name]
            elif callable(mod.__dict__[name]):
                safe_mod.__dict__[name] = self._block_unsafe_function(mod_name, name)

    def _create_safe_modules_config(self):
        """
        Safe modules configurations. Stores whitelisted parts for specified module.
        Supports submodules, e.g. for a safe version of module 'foo' with submodule 'bar'
        specify {'foo': ['bar', 'fun1', 'fun2'], 'foo.bar': ['fun3', fun4']}
        """
        config = {
            'os': ['environ', 'getenv', 'path', 'sep', 'pathsep', 'linesep'],
            'os.path': ['basename', 'commonprefix', 'dirname', 'isabs', 'join', 'normcase',
                        'relpath', 'split', 'splitdrive', 'splitext', 'sep', 'pathsep'],
            'pipes': ['quote'],
        }
        return config

    def _get_safe_module(self, name):
        """
        Returns a safe version of the module.
        """

        assert name in self._safe_modules_config, (
            "Safe version of module %s is not configured." % name)

        # Return the safe version of the module if already created
        if name in self._safe_modules:
            return self._safe_modules[name]

        # Get the normal module, non-empty 'fromlist' prevents returning top-level package
        # (e.g. 'os' would be returned for 'os.path' without it)
        with self._allow_unsafe_import():
            mod = ORIGINAL_IMPORT(name, fromlist=[''])

        # Build a new module for the safe version
        safe_mod = imp.new_module(name)

        # Install whitelisted parts of the module, block the rest to produce errors
        # informing about the safe version.
        self._install_whitelisted_parts(mod, safe_mod, self._safe_modules_config[name])

        # Store the safe version of the module
        self._safe_modules[name] = safe_mod

        return safe_mod

    def _create_import_whitelist(self, project_import_whitelist):
        """
        Creates import whitelist by joining the global whitelist with the project specific one
        defined in '.buckconfig'.
        """

        global_whitelist = ['copy', 're', 'functools', 'itertools', 'json', 'hashlib',
                            'types', 'string', 'ast', '__future__', 'collections',
                            'operator', 'fnmatch', 'copy_reg']

        return set(global_whitelist + project_import_whitelist)

    def _create_custom_import(self):
        """
        Returns customised '__import__' function.
        """

        def _import(name, globals=None, locals=None, fromlist=(), level=-1):
            """
            Custom '__import__' function.
            Returns safe version of a module if configured in '_safe_modules_config'.
            Returns standard module if the module is whitelisted.
            Blocks importing other modules.
            """

            if not fromlist:
                # Return the top-level package if 'fromlist' is empty (e.g. 'os' for 'os.path'),
                # which is how '__import__' works.
                name = name.split('.')[0]

            if name in self._import_whitelist:
                # Importing a module may cause more '__import__' calls if the module uses other
                # modules. Such calls should not be blocked if the top-level import was allowed.
                with self._allow_unsafe_import():
                    return ORIGINAL_IMPORT(name, globals, locals, fromlist, level)

            # Return safe version of the module if possible
            if name in self._safe_modules_config:
                return self._get_safe_module(name)

            raise ImportError(
                'Importing module %s is forbidden. ' % name +
                'If you really need to import this module read about ' +
                'allow_unsafe_import() function that is documented at ' +
                'https://buckbuild.com/function/allow_unsafe_import.html'
            )

        return _import

    @contextmanager
    def _allow_unsafe_import(self, allow=True):
        """
        Controls behavior of 'import' in a context.
        Default value for 'allow' is True, which corresponds to default 'import' behavior, False
        overrides that with 'custom_import()' if sandboxing is enabled.

        :param allow: True if default 'import' behavior should be allowed in the context
        """

        # Override '__import__' function. It might have already been overriden if current file
        # was included by other build file, original '__import__' is stored in 'ORIGINAL_IMPORT'.
        previous_import = __builtin__.__import__
        if allow or not self._enable_build_file_sandboxing:
            __builtin__.__import__ = ORIGINAL_IMPORT
        else:
            __builtin__.__import__ = self._custom_import

        try:
            yield
        finally:
            # Restore previous '__builtin__.__import__'
            __builtin__.__import__ = previous_import

    def _process(self, build_env, path, implicit_includes=None):
        """
        Process a build file or include at the given path.
        """
        if implicit_includes is None:
            implicit_includes = []

        # First check the cache.
        cached = self._cache.get(path)
        if cached is not None:
            return cached

        # Install the build context for this input as the current context.
        self._push_build_env(build_env)

        # The globals dict that this file will be executed under.
        default_globals = {}

        # Install the 'include_defs' function into our global object.
        default_globals['include_defs'] = functools.partial(
            self._include_defs,
            implicit_includes=implicit_includes)

        # Install the 'add_dependency' function into our global object.
        default_globals['add_build_file_dep'] = self._add_build_file_dep

        # Install the 'read_config' function into our global object.
        default_globals['read_config'] = self._read_config

        # Install the 'allow_unsafe_import' function into our global object.
        default_globals['allow_unsafe_import'] = self._allow_unsafe_import

        # If any implicit includes were specified, process them first.
        for include in implicit_includes:
            include_path = self._get_include_path(include)
            inner_env, mod = self._process_include(include_path)
            self._merge_globals(mod, default_globals)
            build_env.includes.add(include_path)
            build_env.includes.update(inner_env.includes)
            build_env.diagnostics.update(inner_env.diagnostics)

        # Build a new module for the given file, using the default globals
        # created above.
        module = imp.new_module(path)
        module.__file__ = path
        module.__dict__.update(default_globals)

        # We don't open this file as binary, as we assume it's a textual source
        # file.
        with open(path, 'r') as f:
            contents = f.read()

        # Enable absolute imports.  This prevents the compiler from trying to
        # do a relative import first, and warning that this module doesn't
        # exist in sys.modules.
        future_features = __future__.absolute_import.compiler_flag
        code = compile(contents, path, 'exec', future_features, 1)

        # Execute code with overriden import
        with self._allow_unsafe_import(False):
            exec(code, module.__dict__)

        # Restore the previous build context.
        self._pop_build_env()

        self._cache[path] = build_env, module
        return build_env, module

    def _process_include(self, path, implicit_includes=None):
        """
        Process the include file at the given path.
        """
        if implicit_includes is None:
            implicit_includes = []

        build_env = IncludeContext()
        return self._process(
            build_env,
            path,
            implicit_includes=implicit_includes)

    def _process_build_file(self, path, implicit_includes=None):
        """
        Process the build file at the given path.
        """
        if implicit_includes is None:
            implicit_includes = []

        # Create the build file context, including the base path and directory
        # name of the given path.
        relative_path_to_build_file = os.path.relpath(
            path, self._project_root).replace('\\', '/')
        len_suffix = -len('/' + self._build_file_name)
        base_path = relative_path_to_build_file[:len_suffix]
        dirname = os.path.dirname(path)

        # If there is a signature failure, then record the error, but do not blow up.
        autodeps = None
        autodeps_file = None
        invalid_signature_error_message = None
        try:
            results = self._try_parse_autodeps(dirname)
            if results:
                (autodeps, autodeps_file) = results
        except InvalidSignatureError as e:
            invalid_signature_error_message = e.message

        build_env = BuildFileContext(
            self._project_root,
            base_path,
            dirname,
            autodeps or {},
            self._allow_empty_globs,
            self._ignore_paths,
            self._watchman_client,
            self._watchman_watch_root,
            self._watchman_project_prefix,
            self._sync_cookie_state,
            self._watchman_error,
            self._watchman_glob_stat_results,
            self._watchman_use_glob_generator)

        # If the .autodeps file has been successfully parsed, then treat it as if it were
        # a file loaded via include_defs() in that a change to the .autodeps file should
        # force all of the build rules in the build file to be invalidated.
        if autodeps_file:
            build_env.includes.add(autodeps_file)

        if invalid_signature_error_message:
            build_env.diagnostics.add(
                Diagnostic(
                    message=invalid_signature_error_message,
                    level='fatal',
                    source='autodeps'))

        return self._process(
            build_env,
            path,
            implicit_includes=implicit_includes)

    def _try_parse_autodeps(self, dirname):
        """
        Returns a tuple of (autodeps dict, autodeps_file string), or None.
        """
        # When we are running as part of `buck autodeps`, we ignore existing BUCK.autodeps files.
        if self._ignore_buck_autodeps_files:
            return None

        autodeps_file = dirname + '/' + self._build_file_name + '.autodeps'
        if not os.path.isfile(autodeps_file):
            return None

        autodeps = self._parse_autodeps(autodeps_file)
        return (autodeps, autodeps_file)

    def _parse_autodeps(self, autodeps_file):
        """
        A BUCK file may have a BUCK.autodeps file that lives alongside it. (If a custom build file
        name is used, then <file-name>.autodeps must be the name of the .autodeps file.)

        The .autodeps file is a JSON file with a special header that is used to sign the file,
        containing a SHA-1 of the contents following the header. If the header does not match the
        contents, an error will be thrown.

        The JSON contains a mapping of build targets (by short name) to lists of build targets that
        represent dependencies. For each mapping, the list of dependencies will be merged with that
        of the original rule declared in the build file. This affords end users the ability to
        partially generate build files.

        :param autodeps_file: Absolute path to the expected .autodeps file.
        :raises InvalidSignatureError:
        """
        with open(autodeps_file, 'r') as stream:
            signature_line = stream.readline()
            contents = stream.read()

        match = GENDEPS_SIGNATURE.match(signature_line)
        if not match:
            raise InvalidSignatureError(
                'Could not extract signature from {0}'.format(autodeps_file))

        signature = match.group(1)
        hash = hashlib.new('sha1')
        hash.update(contents)
        sha1 = hash.hexdigest()

        if (not VERIFY_AUTODEPS_SIGNATURE) or sha1 == signature:
            return json.loads(contents)
        else:
            raise InvalidSignatureError(
                'Signature did not match contents in {0}'.format(autodeps_file))


    def process(self, path, diagnostics):
        """
        Process a build file returning a dict of its rules and includes.
        """
        build_env, mod = self._process_build_file(
            os.path.join(self._project_root, path),
            implicit_includes=self._implicit_includes)

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

        diagnostics.update(build_env.diagnostics)

        return values


class InvalidSignatureError(Exception):
    pass


def cygwin_adjusted_path(path):
    if sys.platform == 'cygwin':
        return subprocess.check_output(['cygpath', path]).rstrip()
    else:
        return path


def encode_result(values, diagnostics, profile):
    result = {'values': values}
    if diagnostics:
        encoded_diagnostics = []
        for d in diagnostics:
            encoded_diagnostics.append({
                'message': d.message,
                'level': d.level,
                'source': d.source,
            })
        result['diagnostics'] = encoded_diagnostics
    if profile is not None:
        result['profile'] = profile
    return bser.dumps(result)


def filter_tb(entries):
    for i in range(len(entries)):
        # Filter out the beginning of the stack trace (any entries including the buck.py file)
        if entries[i][0] != sys.argv[0]:
            return entries[i:]
    return []


def format_traceback_and_exception():
    exc_type, exc_value, exc_traceback = sys.exc_info()
    filtered_traceback = filter_tb(traceback.extract_tb(exc_traceback))
    formatted_traceback = ''.join(traceback.format_list(filtered_traceback))
    formatted_exception = ''.join(traceback.format_exception_only(exc_type, exc_value))
    return formatted_traceback + formatted_exception


def process_with_diagnostics(build_file, build_file_processor, to_parent,
                             should_profile=False):
    build_file = cygwin_adjusted_path(build_file)
    diagnostics = set()
    values = []
    if should_profile:
        profile = cProfile.Profile()
        profile.enable()
    else:
        profile = None
    try:
        values = build_file_processor.process(build_file.rstrip(), diagnostics=diagnostics)
    except Exception as e:
        # Control-C and sys.exit() don't emit diagnostics.
        if not (e is KeyboardInterrupt or e is SystemExit):
            diagnostics.add(
                Diagnostic(
                    message=format_traceback_and_exception(),
                    level='fatal',
                    source='parse'))
        raise e
    finally:
        if profile is not None:
            profile.disable()
            s = StringIO.StringIO()
            pstats.Stats(profile, stream=s).sort_stats('cumulative').print_stats()
            profile_result = s.getvalue()
        else:
            profile_result = None

        to_parent.write(encode_result(values, diagnostics, profile_result))
        to_parent.flush()


def silent_excepthook(exctype, value, tb):
    # We already handle all exceptions by writing them to the parent, so
    # no need to dump them again to stderr.
    pass

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
# to stdout encoded in BSER. That means that printing out other information
# for debugging purposes will break the BSER encoding, so be careful!


def main():
    # Our parent expects to read BSER from our stdout, so if anyone
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
        '--watchman_watch_root',
        action='store',
        type='string',
        dest='watchman_watch_root',
        help='Path to root of watchman watch as returned by `watchman watch-project`.')
    parser.add_option(
        '--watchman_socket_path',
        action='store',
        type='string',
        dest='watchman_socket_path',
        help='Path to Unix domain socket/named pipe as returned by `watchman get-sockname`.')
    parser.add_option(
        '--watchman_project_prefix',
        action='store',
        type='string',
        dest='watchman_project_prefix',
        help='Relative project prefix as returned by `watchman watch-project`.')
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
        '--ignore_buck_autodeps_files',
        action='store_true',
        help='Profile every buck file execution')
    parser.add_option(
        '--profile',
        action='store_true',
        help='Profile every buck file execution')
    parser.add_option(
        '--enable_build_file_sandboxing',
        action='store_true',
        help='Limits abilities of buck files')
    parser.add_option(
        '--build_file_import_whitelist',
        action='append',
        dest='build_file_import_whitelist')
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

    watchman_client = None
    watchman_error = None
    if options.use_watchman_glob:
        import pywatchman
        client_args = {}
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
        watchman_error = pywatchman.WatchmanError

    configs = {}
    if options.config is not None:
        with open(options.config, 'rb') as f:
            for section, contents in bser.loads(f.read()).iteritems():
                for field, value in contents.iteritems():
                    configs[(section, field)] = value

    ignore_paths = []
    if options.ignore_paths is not None:
        with open(options.ignore_paths, 'rb') as f:
            ignore_paths = [make_glob(i) for i in bser.loads(f.read())]

    buildFileProcessor = BuildFileProcessor(
        project_root,
        options.watchman_watch_root,
        options.watchman_project_prefix,
        options.build_file_name,
        options.allow_empty_globs,
        options.ignore_buck_autodeps_files,
        watchman_client,
        watchman_error,
        options.watchman_glob_stat_results,
        options.watchman_use_glob_generator,
        options.enable_build_file_sandboxing,
        project_import_whitelist=options.build_file_import_whitelist or [],
        implicit_includes=options.include or [],
        configs=configs,
        ignore_paths=ignore_paths)

    buildFileProcessor.install_builtins(__builtin__.__dict__)

    # While processing, we'll write exceptions as diagnostic messages
    # to the parent then re-raise them to crash the process. While
    # doing so, we don't want Python's default unhandled exception
    # behavior of writing to stderr.
    orig_excepthook = None
    if options.quiet:
        orig_excepthook = sys.excepthook
        sys.excepthook = silent_excepthook

    # Process the build files with the env var interceptors installed.
    with buildFileProcessor.with_env_interceptors():

        for build_file in args:
            process_with_diagnostics(build_file, buildFileProcessor, to_parent,
                                     should_profile=options.profile)

        # "for ... in sys.stdin" in Python 2.x hangs until stdin is closed.
        for build_file in iter(sys.stdin.readline, ''):
            process_with_diagnostics(build_file, buildFileProcessor, to_parent,
                                     should_profile=options.profile)

    if options.quiet:
        sys.excepthook = orig_excepthook

    # Python tries to flush/close stdout when it quits, and if there's a dead
    # pipe on the other end, it will spit some warnings to stderr. This breaks
    # tests sometimes. Prevent that by explicitly catching the error.
    try:
        to_parent.close()
    except IOError:
        pass


def make_glob(pat):
    if is_special(pat):
        return pat
    return pat + '/**'


def is_special(pat):
    return "*" in pat or "?" in pat or "[" in pat
