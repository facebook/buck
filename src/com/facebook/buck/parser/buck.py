from __future__ import with_statement

import copy
import functools
import optparse
import os
import os.path
import re
import sys

try:
  from com.xhaus.jyson import JysonCodec as json # jython embedded in buck
except ImportError:
  import json # python test case


# TODO(user): upgrade to a jython including os.relpath
def relpath(path, start=os.path.curdir):
  """
  Return a relative filepath to path from the current directory or an optional start point.
  """
  if not path:
    raise ValueError("no path specified")
  start_list = os.path.abspath(start).split(os.path.sep)
  path_list = os.path.abspath(path).split(os.path.sep)
  # Work out how much of the filepath is shared by start and path.
  common = len(os.path.commonprefix([start_list, path_list]))
  rel_list = [os.path.pardir] * (len(start_list) - common) + path_list[common:]
  if not rel_list:
    return os.path.curdir
  return os.path.join(*rel_list)


# When build files are executed, the functions in this file tagged with
# @provide_for_build will be provided in the build file's local symbol table.
#
# When these functions are called from a build file, they will be passed
# a keyword parameter, build_env, which is a dictionary with information about
# the environment of the build file which is currently being processed.
# It contains the following keys:
#
# "BUILD_FILE_DIRECTORY" - The directory containing the build file.
#
# "BASE" - The base path of the build file.
#
# "PROJECT_ROOT" - An absolute path to the project root.
#
# "BUILD_FILE_SYMBOL_TABLE" - The global symbol table of the build file.

BUILD_FUNCTIONS = []
BUILD_RULES_FILE_NAME = 'BUCK'

def provide_for_build(func):
  BUILD_FUNCTIONS.append(func)
  return func


class LazyBuildEnvPartial:
  """Pairs a function with a build environment in which it should be executed.

  Note that although both the function and build environment must be specified
  via the constructor, the build environment may be reassigned after
  construction.

  To call the function with its build environment, use the invoke() method of
  this class, which will forward the arguments from invoke() to the underlying
  function.
  """

  def __init__(self, func, default_build_env):
    self.func = func
    self.build_env = default_build_env

  def invoke(self, *args, **kwargs):
    """Invokes the bound function injecting 'build_env' into **kwargs."""
    updated_kwargs = kwargs.copy()
    updated_kwargs.update({'build_env': self.build_env})
    return self.func(*args, **updated_kwargs)


def make_build_file_symbol_table(build_env):
  """Creates a symbol table with functions decorated by @provide_for_build."""
  symbol_table = {}
  lazy_functions = []
  for func in BUILD_FUNCTIONS:
    func_with_env = LazyBuildEnvPartial(func, build_env)
    symbol_table[func.__name__] = func_with_env.invoke
    lazy_functions.append(func_with_env)
  return {
    'symbol_table': symbol_table,
    'lazy_functions': lazy_functions}


def update_lazy_functions(lazy_functions, build_env):
  """Updates a list of LazyBuildEnvPartials with build_env."""
  for lazy_function in lazy_functions:
    lazy_function.build_env = build_env


def add_rule(rule, build_env):
  # Include the base path of the BUILD file so the reader consuming this JSON will know which BUILD
  # file the rule came from.
  if 'name' not in rule:
    raise ValueError('rules must contain the field \'name\'.  Found %s.' % rule)
  rule_name = rule['name']
  if rule_name in build_env['RULES']:
    raise ValueError('Duplicate rule definition found.  Found %s and %s' %
        (rule, build_env['RULES'][rule_name]))
  rule['buck.base_path'] = build_env['BASE']
  build_env['RULES'][rule_name] = rule


def glob_pattern_to_regex_string(pattern):
    # Replace rules for glob pattern (roughly):
    # . => \\.
    # **/* => (.*)
    # * => [^/]*
    pattern = re.sub(r'\.', '\\.', pattern)
    pattern = pattern.replace('**/*', '(.*)')

    # This handles the case when there is a character preceding the asterisk.
    pattern = re.sub(r'([^\.])\*', '\\1[^/]*', pattern)

    # This handles the case when the asterisk is the first character.
    pattern = re.sub(r'^\*', '[^/]*', pattern)

    pattern = '^' + pattern + '$'
    return pattern


def pattern_to_regex(pattern):
  pattern = glob_pattern_to_regex_string(pattern)
  return re.compile(pattern)


def symlink_aware_walk(base):
  """ Recursive symlink aware version of `os.walk`.

  Will not traverse a symlink that refers to a previously visited ancestor of
  the current directory.
  """
  visited_dirs = set()
  for entry in os.walk(base, topdown=True, followlinks=True):
    (root, dirs, _files) = entry
    realdirpath = os.path.realpath(root)
    if realdirpath in visited_dirs:
      absdirpath = os.path.abspath(root)
      if absdirpath.startswith(realdirpath):
        dirs[:] = []
        continue
    visited_dirs.add(realdirpath)
    yield entry
  raise StopIteration


def splitpath(path, maxdepth=20):
  '''Splits /foo/bar/baz.java into ['', 'foo', 'bar', 'baz.java'].'''
  (head, tail) = os.path.split(path)
  if maxdepth > 0 and head and head != path:
    return splitpath(head, maxdepth - 1) + [tail]
  elif head:
    return [head]
  else:
    return [tail]

def passes_glob_filter(path, inclusions, exclusions):
  '''Checks path against inclusion and exclusion regexes.

  Returns False if the path contains any components which
  start with a dot (mimicking the behavior of glob()).

  Returns False if the path matches any compiled regex
  in the list 'exclusions'.

  Otherwise, returns True if the path matches any compiled
  regex in the list 'inclusions', or False if it does not.'''
  # Ignore dot-files and dot-directories (but not '.' itself).
  for component in splitpath(path):
    if len(component) > 1 and component.startswith('.'):
      return False
  for exclusion in exclusions:
    if exclusion.match(path):
      return False
  for inclusion in inclusions:
    if inclusion.match(path):
      return True
  return False


@provide_for_build
def glob(includes, excludes=[], build_env=None):
  search_base = build_env['BUILD_FILE_DIRECTORY']

  # Ensure the user passes lists of strings rather than just a string.
  assert not isinstance(includes, basestring), \
      "The first argument to glob() must be a list of strings."
  assert not isinstance(excludes, basestring), \
      "The excludes argument must be a list of strings."

  inclusions = [pattern_to_regex(p) for p in includes]
  exclusions = [pattern_to_regex(p) for p in excludes]

  # Return the filtered set of includes as an array.
  paths = []

  def check_path(path):
    if passes_glob_filter(path, inclusions, exclusions):
      paths.append(path)

  for root, dirs, files in symlink_aware_walk(search_base):
    if len(files) == 0:
      continue
    relative_root = relpath(root, search_base)
    # The regexes generated by glob_pattern_to_regex_string don't
    # expect a leading './'
    if relative_root == '.':
      for file_path in files:
        check_path(file_path)
    else:
      relative_root += '/'
      for file_path in files:
        relative_path = relative_root + file_path
        check_path(relative_path)

  return paths


@provide_for_build
def genfile(src, build_env=None):
  return 'BUCKGEN:' + src


@provide_for_build
def java_library(
    name,
    srcs=[],
    resources=[],
    export_deps=None,
    exported_deps=[],
    source='6',
    target='6',
    proguard_config=None,
    deps=[],
    visibility=[],
    build_env=None):
  add_rule({
    'type' : 'java_library',
    'name' : name,
    'srcs' : srcs,
    'resources' : resources,
    # Temporary hack to let repos cut over to new style of exporting deps.
    'exported_deps' : deps if export_deps else exported_deps,
    'source' : source,
    'target' : target,
    'proguard_config' : proguard_config,
    'deps' : deps + exported_deps,
    'visibility' : visibility,
  }, build_env)


@provide_for_build
def java_test(
    name,
    srcs=[],
    labels=[],
    resources=[],
    source='6',
    target='6',
    vm_args=[],
    source_under_test=[],
    contacts=[],
    deps=[],
    visibility=[],
    build_env=None):
  add_rule({
    'type' : 'java_test',
    'name' : name,
    'srcs' : srcs,
    'labels': labels,
    'resources' : resources,
    'source' : source,
    'target' : target,
    'vm_args' : vm_args,
    'source_under_test' : source_under_test,
    'contacts' : contacts,
    'deps' : deps,
    'visibility' : visibility,
  }, build_env)


@provide_for_build
def robolectric_test(
    name,
    srcs=[],
    labels=[],
    resources=[],
    vm_args=[],
    source_under_test=[],
    contacts=[],
    deps=[],
    visibility=[],
    build_env=None):
  add_rule({
    'type' : 'robolectric_test',
    'name' : name,
    'srcs' : srcs,
    'labels': labels,
    'resources' : resources,
    'vm_args' : vm_args,
    'source_under_test' : source_under_test,
    'contacts' : contacts,
    'deps' : deps,
    'visibility' : visibility,
  }, build_env)


@provide_for_build
def java_binary(
  name,
  main_class=None,
  manifest_file=None,
  deps=[],
  visibility=[],
  build_env=None):

  add_rule({
    'type' : 'java_binary',
    'name' : name,
    'manifest_file': manifest_file,
    'main_class' : main_class,
    'deps' : deps,
    'visibility' : visibility,
  }, build_env)


@provide_for_build
def prebuilt_jar(
    name,
    binary_jar,
    source_jar=None,
    javadoc_url=None,
    deps=[],
    visibility=[],
    build_env=None):
  add_rule({
    'type': 'prebuilt_jar',
    'name': name,
    'binary_jar': binary_jar,
    'source_jar': source_jar,
    'javadoc_url': javadoc_url,
    'deps': deps,
    'visibility' : visibility,
  }, build_env)


@provide_for_build
def android_library(
    name,
    srcs=[],
    resources=[],
    exported_deps=[],
    manifest=None,
    proguard_config=None,
    deps=[],
    visibility=[],
    build_env=None):
  add_rule({
    'type' : 'android_library',
    'name' : name,
    'srcs' : srcs,
    'exported_deps' : exported_deps,
    'resources' : resources,
    'manifest' : manifest,
    'proguard_config' : proguard_config,
    'deps' : deps + exported_deps,
    'visibility' : visibility,
  }, build_env)


@provide_for_build
def android_resource(
    name,
    res=None,
    has_whitelisted_strings=False,
    package=None,
    assets=None,
    manifest=None,
    deps=[],
    visibility=[],
    build_env=None):
  if res:
    res_srcs = glob([res + '/**/*'], build_env=build_env)
  else:
    res_srcs = None

  if assets:
    assets_srcs = glob([assets + '/**/*'], build_env=build_env)
  else:
    assets_srcs = None

  add_rule({
    'type' : 'android_resource',
    'name' : name,
    'res' : res,
    'has_whitelisted_strings' : has_whitelisted_strings,
    'res_srcs' : res_srcs,
    'package' : package,
    'assets' : assets,
    'assets_srcs' : assets_srcs,
    'manifest' : manifest,
    'deps' : deps,
    'visibility' : visibility,
  }, build_env)


@provide_for_build
def prebuilt_native_library(
    name,
    native_libs=None,
    is_asset=False,
    deps=[],
    visibility=[],
    build_env=None):
  add_rule({
    'type' : 'prebuilt_native_library',
    'name' : name,
    'native_libs' : native_libs,
    'is_asset' : is_asset,
    'deps' : deps,
    'visibility' : visibility,
  }, build_env)


@provide_for_build
def android_binary(
      name,
      manifest,
      target,
      keystore,
      package_type='debug',
      no_dx=[],
      use_split_dex=False,
      use_linear_alloc_split_dex=False,
      minimize_primary_dex_size=False,
      disable_pre_dex=False,
      dex_compression='jar',
      use_android_proguard_config_with_optimizations=False,
      proguard_config=None,
      resource_compression=None,
      primary_dex_patterns=None,
      primary_dex_classes_file=None,
      # By default, assume we have 5MB of linear alloc,
      # 1MB of which is taken up by the framework, so that leaves 4MB.
      linear_alloc_hard_limit=4 * 1024 * 1024,
      resource_filter=None,
      cpu_filters=[],
      preprocess_java_classes_deps=[],
      preprocess_java_classes_bash=None,
      deps=[],
      visibility=[],
      build_env=None):
  add_rule({
    'type' : 'android_binary',
    'name' : name,
    'manifest' : manifest,
    'target' : target,
    'keystore' : keystore,
    'package_type' : package_type,
    'no_dx' : no_dx,
    'use_split_dex': use_split_dex,
    'use_linear_alloc_split_dex': use_linear_alloc_split_dex,
    'minimize_primary_dex_size': minimize_primary_dex_size,
    'disable_pre_dex' : disable_pre_dex,
    'dex_compression': dex_compression,
    'use_android_proguard_config_with_optimizations':
        use_android_proguard_config_with_optimizations,
    'proguard_config' : proguard_config,
    'resource_compression' : resource_compression,
    'primary_dex_patterns' : primary_dex_patterns,
    'primary_dex_classes_file' : primary_dex_classes_file,
    'linear_alloc_hard_limit' : linear_alloc_hard_limit,
    'resource_filter' : resource_filter,
    'cpu_filters' : cpu_filters,
    'preprocess_java_classes_deps' : preprocess_java_classes_deps,
    'preprocess_java_classes_bash' : preprocess_java_classes_bash,
    'classpath_deps' : deps,
    # Always include the keystore as a dep, as it should be built before this rule.
    'deps' : deps + [keystore] + preprocess_java_classes_deps,
    'visibility' : visibility,
  }, build_env)


@provide_for_build
def android_instrumentation_apk(
    name,
    manifest,
    apk,
    deps=[],
    visibility=[],
    build_env=None):
  add_rule({
    'type' : 'android_instrumentation_apk',
    'name' : name,
    'manifest' : manifest,
    'apk' : apk,
    'classpath_deps' : deps,
    'deps' : deps + [ apk ],
    'visibility' : visibility,
  }, build_env)


@provide_for_build
def ndk_library(
    name,
    flags=None,
    is_asset=False,
    deps=[],
    visibility=[],
    build_env=None):

  EXTENSIONS = ("mk", "h", "hpp", "c", "cpp", "cc", "cxx")
  srcs = glob(["**/*.%s" % ext for ext in EXTENSIONS], build_env=build_env)
  add_rule({
    'type' : 'ndk_library',
    'name' : name,
    'srcs' : srcs,
    'flags' : flags,
    'is_asset' : is_asset,
    'deps' : deps,
    'visibility' : visibility,
  }, build_env)


@provide_for_build
def python_binary(
    name,
    main=None,
    deps=[],
    visibility=[],
    build_env=None):
  add_rule({
    'type' : 'python_binary',
    'name' : name,
    'main' : main,
    'deps' : deps,
    'visibility' : visibility,
  }, build_env)


@provide_for_build
def keystore(
    name,
    store,
    properties,
    deps=[],
    visibility=[],
    build_env=None):
  add_rule({
    'type' : 'keystore',
    'name' : name,
    'store' : store,
    'properties' : properties,
    'deps' : deps,
    'visibility' : visibility,
  }, build_env)


@provide_for_build
def gen_parcelable(
    name,
    srcs,
    deps=[],
    visibility=[],
    build_env=None):
  add_rule({
    'type' : 'gen_parcelable',
    'name' : name,
    'srcs' : srcs,
    'deps' : deps,
    'visibility' : visibility,
  }, build_env)


@provide_for_build
def genrule(name,
    out,
    cmd=None,
    bash=None,
    cmd_exe=None,
    srcs=[],
    deps=[],
    visibility=[],
    build_env=None):
  add_rule({
    'type' : 'genrule',
    'name' : name,
    'srcs' : srcs,
    'cmd' : cmd,
    'bash' : bash,
    'cmd_exe' : cmd_exe,
    'out' : out,
    'deps' : deps,
    'visibility' : visibility,
  }, build_env)


@provide_for_build
def apk_genrule(name,
    srcs,
    apk,
    out,
    cmd=None,
    bash=None,
    cmd_exe=None,
    deps=[],
    visibility=[],
    build_env=None):
  # Always include the apk as a dep, as it should be built before this rule.
  deps = deps + [apk]
  add_rule({
    'type' : 'apk_genrule',
    'name' : name,
    'srcs' : srcs,
    'apk': apk,
    'cmd' : cmd,
    'bash' : bash,
    'cmd_exe' : cmd_exe,
    'deps' : deps,
    'visibility' : visibility,
  }, build_env)


@provide_for_build
def sh_binary(
    name,
    main,
    resources=[],
    deps=[],
    visibility=[],
    build_env=None):
  add_rule({
    'type' : 'sh_binary',
    'name' : name,
    'main' : main,
    'resources' : resources,
    'deps' : deps,
    'visibility' : visibility,
  }, build_env)


@provide_for_build
def sh_test(name, test, labels=[], deps=[], visibility=[], build_env=None):
  add_rule({
    'type' : 'sh_test',
    'name' : name,
    'test' : test,
    'labels' : labels,
    'deps' : deps,
    'visibility' : visibility,
  }, build_env)


@provide_for_build
def include_defs(name, build_env=None):
  """Loads a file in the context of the current build file.
  Name must begin with "//" and references a file relative to the project root.

  An example is the build file //first-party/orca/orcaapp/BUILD contains
  include_defs('//BUILD_DEFS')
  which loads a list called NO_DX which can then be used in the build file.
  """
  if name[:2] != '//':
    raise ValueError('include_defs argument "%s" must begin with //' % name)
  relative_path = name[2:]
  include_file = os.path.join(build_env['PROJECT_ROOT'], relative_path)
  build_env['INCLUDES'].append(include_file)
  execfile(include_file, build_env['BUILD_FILE_SYMBOL_TABLE'])


@provide_for_build
def project_config(
    src_target=None,
    src_roots=[],
    test_target=None,
    test_roots=[],
    is_intellij_plugin=False,
    build_env=None):
  deps = []
  if src_target:
    deps.append(src_target)
  if test_target:
    deps.append(test_target)
  add_rule({
    'type' : 'project_config',
    'name' : 'project_config',
    'src_target' : src_target,
    'src_roots' : src_roots,
    'test_target' : test_target,
    'test_roots' : test_roots,
    'is_intellij_plugin': is_intellij_plugin,
    'deps' : deps,
    'visibility' : [],
  }, build_env)


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
  return build_env['BASE']


@provide_for_build
def add_deps(name, deps=[], build_env=None):
  if name not in build_env['RULES']:
    raise ValueError('Invoked \'add_deps\' on non-existent rule %s.' % name)

  rule = build_env['RULES'][name]
  if 'deps' not in rule:
    raise ValueError('Invoked \'add_deps\' on rule %s that has no \'deps\' field' % name)
  rule['deps'] = rule['deps'] + deps


def strip_none_entries(rules):
  return [dict((k,v) for k, v in rule.iteritems() if v is not None) for rule in rules]


class BuildFileProcessor:
  def __init__(self, project_root, includes, server, strip_none):
    self.project_root = project_root
    self.includes = includes
    self.server = server
    self.strip_none = strip_none
    self.len_suffix = -len('/' + BUILD_RULES_FILE_NAME)

    # Create root_build_env
    build_env = {}
    build_env['PROJECT_ROOT'] = self.project_root
    build_symbols = make_build_file_symbol_table(build_env)
    build_env['BUILD_FILE_SYMBOL_TABLE'] = build_symbols['symbol_table']
    build_env['LAZY_FUNCTIONS'] = build_symbols['lazy_functions']
    build_env['INCLUDES'] = []

    # If there are any default includes, evaluate those first to populate the
    # build_env.
    for include in self.includes:
      include_defs(include, build_env)

    self.root_build_env = build_env

  def process(self, build_file):
    """Process an individual build file and output JSON of result to stdout."""

    # Reset build_env for each build file so that the variables declared in the
    # build file or the files in includes through include_defs() don't pollute
    # the namespace for subsequent build files.
    build_env = copy.copy(self.root_build_env)
    relative_path_to_build_file = relpath(build_file, self.project_root).replace('\\', '/')
    build_env['BASE'] = relative_path_to_build_file[:self.len_suffix]
    build_env['BUILD_FILE_DIRECTORY'] = os.path.dirname(build_file)
    build_env['RULES'] = {}

    # Copy BUILD_FILE_SYMBOL_TABLE over.  This is the only dict that we need
    # a sperate copy of since update_lazy_functions will modify it.
    build_env['BUILD_FILE_SYMBOL_TABLE'] = copy.copy(
        self.root_build_env['BUILD_FILE_SYMBOL_TABLE'])

    # Re-apply build_env to the rules added in this file with
    # @provide_for_build.
    update_lazy_functions(build_env['LAZY_FUNCTIONS'], build_env)
    execfile(os.path.join(self.project_root, build_file),
             build_env['BUILD_FILE_SYMBOL_TABLE'])

    values = build_env['RULES'].values()
    if self.strip_none:
     # Filter out keys with a value of "None" from the final rule definition.
     values = strip_none_entries(values)
    values.append({"__includes": [build_file] + build_env['INCLUDES']})
    if self.server:
      print json.dumps(values)
    else:
      for value in values:
        print json.dumps(value)

# Inexplicably, this script appears to run faster when the arguments passed into it are absolute
# paths. However, we want the "buck.base_path" property of each rule to be printed out to be the
# base path of the build target that identifies the rule. That means that when parsing a BUILD file,
# we must know its path relative to the root of the project to produce the base path.
#
# To that end, the first argument to this script must be an absolute path to the project root.
# It must be followed by one or more absolute paths to BUILD files under the project root.
# If no paths to BUILD files are specified, then it will traverse the project root for BUILD files,
# excluding directories of generated files produced by Buck.
#
# All of the build rules that are parsed from the BUILD files will be printed to stdout by a JSON
# parser. That means that printing out other information for debugging purposes will likely break
# the JSON parsing, so be careful!
def main():
  parser = optparse.OptionParser()
  parser.add_option('--project_root', action='store', type='string', dest='project_root')
  parser.add_option('--include', action='append', dest='include')
  parser.add_option('--ignore_path', action='append', dest='ignore_paths')
  parser.add_option('--server', action='store_true', dest='server',
      help='Invoke as a server to parse individual BUCK files on demand.')
  parser.add_option('--strip_none', action='store_true', dest='strip_none',
      help='Invoke as a server to parse individual BUCK files on demand.')
  (options, args) = parser.parse_args()

  # Even though project_root is absolute path, it may not be concise. For example, it might be
  # like "C:\project\.\rule". We normalize it in order to make it consistent with ignore_paths.
  project_root = os.path.abspath(options.project_root)

  build_files = []
  if args:
    # The user has specified which build files to parse.
    build_files = args
  elif not options.server:
    # Find all of the build files in the project root. Symlinks will not be traversed.
    # Search must be done top-down so that directory filtering works as desired.
    # options.ignore_paths may contain /, which is needed to be normalized in order to do string
    # pattern matching.
    ignore_paths = [os.path.abspath(os.path.join(project_root, d))
        for d in options.ignore_paths or []]
    build_files = []
    for dirpath, dirnames, filenames in symlink_aware_walk(project_root):
      # Do not walk directories that contain generated/non-source files.
      # All modifications to dirnames must occur in-place.
      dirnames[:] = [d for d in dirnames if not (os.path.join(dirpath, d) in ignore_paths)]

      if BUILD_RULES_FILE_NAME in filenames:
        build_file = os.path.join(dirpath, BUILD_RULES_FILE_NAME)
        build_files.append(build_file)

  buildFileProcessor = BuildFileProcessor(project_root,
      options.include or [],
      options.server,
      options.strip_none)

  for build_file in build_files:
    buildFileProcessor.process(build_file)

  if options.server:
    # Apparently for ... in sys.stdin doesn't work with Jython when a custom stdin is
    # provided by the caller in Java-land.  Claims that sys.stdin is a filereader which doesn't
    # offer an iterator.
    for build_file in iter(sys.stdin.readline, ''):
      buildFileProcessor.process(build_file.rstrip())
