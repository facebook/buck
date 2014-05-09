from __future__ import with_statement

import copy
import fnmatch
import functools
import glob as glob_module
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

def split_path(path):
  """Splits /foo/bar/baz.java into ['', 'foo', 'bar', 'baz.java']."""
  return path.split(os.path.sep);

def well_formed_tokens(tokens):
  """Verify that a tokenized path contains no empty token."""
  return all(tokens)

def path_join(path, element):
  """Add a new path element to a path, assuming None encodes the empty path."""
  if path is None:
    return element
  return path + os.path.sep + element

def glob_walk_internal(normpath_join, iglob, isresult, visited, tokens, path, normpath):
  """Recursive routine for glob_walk.

  'visited' is initially the empty set.
  'tokens' is the list of glob elements yet to be traversed, e.g. ['**', '*.java'].
  'path', initially None, is the path being constructed.
  'normpath', initially os.path.realpath(root), is the os.path.realpath()-normalization of the path being constructed.
  'normpath_join(normpath, element)' is the os.path.realpath()-normalization of path_join(normpath, element)
  'iglob(pattern)' should behave like glob.iglob if 'path' were relative to the current directory
  'isresult(path)' should verify that path is valid as a result (typically calls os.path.isfile)
  """
  # Force halting despite symlinks.
  key = (tuple(tokens), normpath)
  if key in visited:
    return
  visited.add(key)

  # Base case.
  if not tokens:
    if isresult(path):
      yield path
    return
  token = tokens[0]
  next_tokens = tokens[1:]

  # Special glob token, equivalent to zero or more consecutive '*'
  if token == '**':
    for x in glob_walk_internal(normpath_join, iglob, isresult, visited, next_tokens, path, normpath):
      yield x
    for child in iglob(path_join(path, '*')):
      for x in glob_walk_internal(normpath_join, iglob, isresult, visited, tokens, child, normpath_join(normpath, child)):
        yield x

  # Usual glob pattern.
  else:
    for child in iglob(path_join(path, token)):
      for x in glob_walk_internal(normpath_join, iglob, isresult, visited, next_tokens, child, normpath_join(normpath, child)):
        yield x

def glob_walk(pattern, root, include_dotfiles=False):
  """Walk the path hierarchy, following symlinks, and emit relative paths to plain files matching 'pattern'.

  Patterns can be any combination of chars recognized by shell globs.
  The special path token '**' expands to zero or more consecutive '*'.
  E.g. '**/foo.java' will match the union of 'foo.java', '*/foo.java.', '*/*/foo.java', etc.

  Names starting with dots will not be matched by '?', '*' and '**' unless include_dotfiles=True
  """
  # os.path.realpath()-normalized version of path_join
  def normpath_join(normpath, element):
    newpath = normpath + os.path.sep + element
    if os.path.islink(newpath):
      return os.path.realpath(newpath)
    else:
      return newpath

  # Relativized version of glob.iglob
  # Note that glob.iglob already optimizes paths with no special char.
  root_len = len(os.path.join(root, ''))
  special_rules_for_dots = ((r'^\*', '.*'), (r'^\?', '.'), (r'/\*', '/.*'), (r'/\?', '/.')) if include_dotfiles else []
  def iglob(pattern):
    for p in glob_module.iglob(os.path.join(root, pattern)):
      yield p[root_len:]
    # Additional pass for dots.
    # Note that there is at most one occurrence of one problematic pattern
    for rule in special_rules_for_dots:
      special = re.sub(rule[0], rule[1], pattern, count=1)
      # Using pointer equality for speed: http://docs.python.org/2.7/library/re.html#re.sub
      if special is not pattern:
        for p in glob_module.iglob(os.path.join(root, special)):
          yield p[root_len:]
        break

  # Relativized version of os.path.isfile
  def isresult(path):
    if path is None:
      return False
    return os.path.isfile(os.path.join(root, path))

  visited = set()
  tokens = split_path(pattern)
  assert well_formed_tokens(tokens), "Glob patterns cannot be empty, start or end with a slash, or contain consecutive slashes."
  return glob_walk_internal(normpath_join, iglob, isresult, visited, tokens, None, os.path.realpath(root))

def glob_match_internal(include_dotfiles, tokens, chunks):
  """Recursive routine for glob_match.

  Works as glob_walk_internal but on a linear path instead of some filesystem.
  """
  # Base case(s).
  if not tokens:
    return True if not chunks else False
  token = tokens[0]
  next_tokens = tokens[1:]
  if not chunks:
    return glob_match_internal(include_dotfiles, next_tokens, chunks) if token == '**' else False
  chunk = chunks[0]
  next_chunks = chunks[1:]

  # Plain name (possibly empty)
  if not glob_module.has_magic(token):
    return token == chunk and glob_match_internal(include_dotfiles, next_tokens, next_chunks)

  # Special glob token.
  elif token == '**':
    if glob_match_internal(include_dotfiles, next_tokens, chunks):
      return True
    # Simulate glob pattern '*'
    if not include_dotfiles and chunk and chunk[0] == '.':
      return False
    return glob_match_internal(include_dotfiles, tokens, next_chunks)

  # Usual glob pattern.
  else:
    # We use the same internal library fnmatch as the original code:
    #    http://hg.python.org/cpython/file/2.7/Lib/glob.py#l76
    # TODO(user): to match glob.glob, '.*' should not match '.' or '..'
    if not include_dotfiles and token[0] != '.' and chunk and chunk[0] == '.':
      return False
    return fnmatch.fnmatch(chunk, token) and glob_match_internal(include_dotfiles, next_tokens, next_chunks)

def glob_match(pattern, path, include_dotfiles=False):
  """Checks if a given (non necessarily existing) path matches a 'pattern'.

  Patterns can include the same special tokens as glob_walk.
  Paths and patterns are seen as a list of path elements delimited by '/'.
  E.g. '/' does not match '', but '*' does.
  """
  tokens = split_path(pattern)
  chunks = split_path(path)
  return glob_match_internal(include_dotfiles, tokens, chunks)

@provide_for_build
def glob(includes, excludes=[], include_dotfiles=False, build_env=None):
  search_base = build_env['BUILD_FILE_DIRECTORY']

  # Ensure the user passes lists of strings rather than just a string.
  assert not isinstance(includes, basestring), \
      "The first argument to glob() must be a list of strings."
  assert not isinstance(excludes, basestring), \
      "The excludes argument must be a list of strings."

  paths = set()
  for pattern in includes:
    for path in glob_walk(pattern, search_base, include_dotfiles=include_dotfiles):
      paths.add(path)

  def exclusion(path):
    exclusions = (e for e in excludes if glob_match(e, path, include_dotfiles=include_dotfiles))
    return next(exclusions, None)

  paths = [p for p in paths if not exclusion(p)]
  paths.sort()
  return paths;


@provide_for_build
def genfile(src, build_env=None):
  return 'BUCKGEN:' + src


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

  # Python tries to flush/close stdout when it quits, and if there's a dead
  # pipe on the other end, it will spit some warnings to stderr. This breaks
  # tests sometimes. Prevent that by explicitly catching the error.
  try:
    sys.stdout.close()
  except IOError:
    pass
