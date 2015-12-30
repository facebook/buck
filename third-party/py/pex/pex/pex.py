# Copyright 2014 Pants project contributors (see CONTRIBUTORS.md).
# Licensed under the Apache License, Version 2.0 (see LICENSE).

from __future__ import absolute_import, print_function

import os
import subprocess
import sys
from contextlib import contextmanager
from distutils import sysconfig
from site import USER_SITE

import pkg_resources
from pkg_resources import EntryPoint, WorkingSet, find_distributions

from .common import die
from .compatibility import exec_function
from .environment import PEXEnvironment
from .finders import get_entry_point_from_console_script, get_script_from_distributions
from .interpreter import PythonInterpreter
from .orderedset import OrderedSet
from .pex_info import PexInfo
from .tracer import TRACER
from .variables import ENV


class DevNull(object):
  def __init__(self):
    pass

  def write(self, *args, **kw):
    pass


class PEX(object):  # noqa: T000
  """PEX, n. A self-contained python environment."""

  class Error(Exception): pass
  class NotFound(Error): pass

  @classmethod
  def clean_environment(cls):
    try:
      del os.environ['MACOSX_DEPLOYMENT_TARGET']
    except KeyError:
      pass
    # Cannot change dictionary size during __iter__
    filter_keys = [key for key in os.environ if key.startswith('PEX_')]
    for key in filter_keys:
      del os.environ[key]

  def __init__(self, pex=sys.argv[0], interpreter=None, env=ENV):
    self._pex = pex
    self._interpreter = interpreter or PythonInterpreter.get()
    self._pex_info = PexInfo.from_pex(self._pex)
    self._pex_info_overrides = PexInfo.from_env(env=env)
    self._vars = env
    self._envs = []
    self._working_set = None

  def _activate(self):
    if not self._working_set:
      working_set = WorkingSet([])

      # set up the local .pex environment
      pex_info = self._pex_info.copy()
      pex_info.update(self._pex_info_overrides)
      self._envs.append(PEXEnvironment(self._pex, pex_info))

      # set up other environments as specified in PEX_PATH
      for pex_path in filter(None, self._vars.PEX_PATH.split(os.pathsep)):
        pex_info = PexInfo.from_pex(pex_path)
        pex_info.update(self._pex_info_overrides)
        self._envs.append(PEXEnvironment(pex_path, pex_info))

      # activate all of them
      for env in self._envs:
        for dist in env.activate():
          working_set.add(dist)

      self._working_set = working_set

    return self._working_set

  @classmethod
  def _extras_paths(cls):
    standard_lib = sysconfig.get_python_lib(standard_lib=True)
    try:
      makefile = sysconfig.parse_makefile(sysconfig.get_makefile_filename())
    except (AttributeError, IOError):
      # This is not available by default in PyPy's distutils.sysconfig or it simply is
      # no longer available on the system (IOError ENOENT)
      makefile = {}
    extras_paths = filter(None, makefile.get('EXTRASPATH', '').split(':'))
    for path in extras_paths:
      yield os.path.join(standard_lib, path)

  @classmethod
  def _site_libs(cls):
    try:
      from site import getsitepackages
      site_libs = set(getsitepackages())
    except ImportError:
      site_libs = set()
    site_libs.update([sysconfig.get_python_lib(plat_specific=False),
                      sysconfig.get_python_lib(plat_specific=True)])
    real_site_libs = set(os.path.realpath(path) for path in site_libs)
    return site_libs | real_site_libs

  @classmethod
  def _tainted_path(cls, path, site_libs):
    paths = frozenset([path, os.path.realpath(path)])
    return any(path.startswith(site_lib) for site_lib in site_libs for path in paths)

  @classmethod
  def minimum_sys_modules(cls, site_libs, modules=None):
    """Given a set of site-packages paths, return a "clean" sys.modules.

    When importing site, modules within sys.modules have their __path__'s populated with
    additional paths as defined by *-nspkg.pth in site-packages, or alternately by distribution
    metadata such as *.dist-info/namespace_packages.txt.  This can possibly cause namespace
    packages to leak into imports despite being scrubbed from sys.path.

    NOTE: This method mutates modules' __path__ attributes in sys.module, so this is currently an
    irreversible operation.
    """

    modules = modules or sys.modules
    new_modules = {}

    for module_name, module in modules.items():
      # builtins can stay
      if not hasattr(module, '__path__'):
        new_modules[module_name] = module
        continue

      # Pop off site-impacting __path__ elements in-place.
      for k in reversed(range(len(module.__path__))):
        if cls._tainted_path(module.__path__[k], site_libs):
          TRACER.log('Scrubbing %s.__path__: %s' % (module_name, module.__path__[k]), V=3)
          module.__path__.pop(k)

      # It still contains path elements not in site packages, so it can stay in sys.modules
      if module.__path__:
        new_modules[module_name] = module

    return new_modules

  @classmethod
  def minimum_sys_path(cls, site_libs):
    site_distributions = OrderedSet()
    user_site_distributions = OrderedSet()

    def all_distribution_paths(path):
      locations = set(dist.location for dist in find_distributions(path))
      return set([path]) | locations | set(os.path.realpath(path) for path in locations)

    for path_element in sys.path:
      if cls._tainted_path(path_element, site_libs):
        TRACER.log('Tainted path element: %s' % path_element)
        site_distributions.update(all_distribution_paths(path_element))
      else:
        TRACER.log('Not a tainted path element: %s' % path_element, V=2)

    user_site_distributions.update(all_distribution_paths(USER_SITE))

    for path in site_distributions:
      TRACER.log('Scrubbing from site-packages: %s' % path)

    for path in user_site_distributions:
      TRACER.log('Scrubbing from user site: %s' % path)

    scrub_paths = site_distributions | user_site_distributions
    scrubbed_sys_path = list(OrderedSet(sys.path) - scrub_paths)
    scrub_from_importer_cache = filter(
      lambda key: any(key.startswith(path) for path in scrub_paths),
      sys.path_importer_cache.keys())
    scrubbed_importer_cache = dict((key, value) for (key, value) in sys.path_importer_cache.items()
      if key not in scrub_from_importer_cache)

    for importer_cache_entry in scrub_from_importer_cache:
      TRACER.log('Scrubbing from path_importer_cache: %s' % importer_cache_entry, V=2)

    return scrubbed_sys_path, scrubbed_importer_cache

  @classmethod
  def minimum_sys(cls):
    """Return the minimum sys necessary to run this interpreter, a la python -S.

    :returns: (sys.path, sys.path_importer_cache, sys.modules) tuple of a
      bare python installation.
    """
    site_libs = set(cls._site_libs())
    for site_lib in site_libs:
      TRACER.log('Found site-library: %s' % site_lib)
    for extras_path in cls._extras_paths():
      TRACER.log('Found site extra: %s' % extras_path)
      site_libs.add(extras_path)
    site_libs = set(os.path.normpath(path) for path in site_libs)

    sys_path, sys_path_importer_cache = cls.minimum_sys_path(site_libs)
    sys_modules = cls.minimum_sys_modules(site_libs)

    return sys_path, sys_path_importer_cache, sys_modules

  @classmethod
  @contextmanager
  def patch_pkg_resources(cls, working_set):
    """Patch pkg_resources given a new working set."""
    def patch(working_set):
      pkg_resources.working_set = working_set
      pkg_resources.require = working_set.require
      pkg_resources.iter_entry_points = working_set.iter_entry_points
      pkg_resources.run_script = pkg_resources.run_main = working_set.run_script
      pkg_resources.add_activation_listener = working_set.subscribe

    old_working_set = pkg_resources.working_set
    patch(working_set)
    try:
      yield
    finally:
      patch(old_working_set)

  # Thar be dragons -- when this contextmanager exits, the interpreter is
  # potentially in a wonky state since the patches here (minimum_sys_modules
  # for example) actually mutate global state.  This should not be
  # considered a reversible operation despite being a contextmanager.
  @classmethod
  @contextmanager
  def patch_sys(cls):
    """Patch sys with all site scrubbed."""
    def patch_dict(old_value, new_value):
      old_value.clear()
      old_value.update(new_value)

    def patch_all(path, path_importer_cache, modules):
      sys.path[:] = path
      patch_dict(sys.path_importer_cache, path_importer_cache)
      patch_dict(sys.modules, modules)

    old_sys_path, old_sys_path_importer_cache, old_sys_modules = (
        sys.path[:], sys.path_importer_cache.copy(), sys.modules.copy())
    new_sys_path, new_sys_path_importer_cache, new_sys_modules = cls.minimum_sys()

    patch_all(new_sys_path, new_sys_path_importer_cache, new_sys_modules)
    yield

  def _wrap_coverage(self, runner, *args):
    if not self._vars.PEX_COVERAGE and self._vars.PEX_COVERAGE_FILENAME is None:
      runner(*args)
      return

    try:
      import coverage
    except ImportError:
      die('Could not bootstrap coverage module, aborting.')

    pex_coverage_filename = self._vars.PEX_COVERAGE_FILENAME
    if pex_coverage_filename is not None:
      cov = coverage.coverage(data_file=pex_coverage_filename)
    else:
      cov = coverage.coverage(data_suffix=True)

    TRACER.log('Starting coverage.')
    cov.start()

    try:
      runner(*args)
    finally:
      TRACER.log('Stopping coverage')
      cov.stop()

      # TODO(wickman) Post-process coverage to elide $PEX_ROOT and make
      # the report more useful/less noisy.  #89
      if pex_coverage_filename:
        cov.save()
      else:
        cov.report(show_missing=False, ignore_errors=True, file=sys.stdout)

  def _wrap_profiling(self, runner, *args):
    if not self._vars.PEX_PROFILE and self._vars.PEX_PROFILE_FILENAME is None:
      runner(*args)
      return

    pex_profile_filename = self._vars.PEX_PROFILE_FILENAME
    pex_profile_sort = self._vars.PEX_PROFILE_SORT
    try:
      import cProfile as profile
    except ImportError:
      import profile

    profiler = profile.Profile()

    try:
      return profiler.runcall(runner, *args)
    finally:
      if pex_profile_filename is not None:
        profiler.dump_stats(pex_profile_filename)
      else:
        profiler.print_stats(sort=pex_profile_sort)

  def execute(self):
    """Execute the PEX.

    This function makes assumptions that it is the last function called by
    the interpreter.
    """
    teardown_verbosity = self._vars.PEX_TEARDOWN_VERBOSE
    try:
      with self.patch_sys():
        working_set = self._activate()
        TRACER.log('PYTHONPATH contains:')
        for element in sys.path:
          TRACER.log('  %c %s' % (' ' if os.path.exists(element) else '*', element))
        TRACER.log('  * - paths that do not exist or will be imported via zipimport')
        with self.patch_pkg_resources(working_set):
          self._wrap_coverage(self._wrap_profiling, self._execute)
    except Exception:
      # Allow the current sys.excepthook to handle this app exception before we tear things down in
      # finally, then reraise so that the exit status is reflected correctly.
      sys.excepthook(*sys.exc_info())
      raise
    except SystemExit as se:
      # Print a SystemExit error message, avoiding a traceback in python3.
      # This must happen here, as sys.stderr is about to be torn down
      if not isinstance(se.code, int) and se.code is not None:
        print(se.code, file=sys.stderr)
      raise
    finally:
      # squash all exceptions on interpreter teardown -- the primary type here are
      # atexit handlers failing to run because of things such as:
      #   http://stackoverflow.com/questions/2572172/referencing-other-modules-in-atexit
      if not teardown_verbosity:
        sys.stderr.flush()
        sys.stderr = DevNull()
        sys.excepthook = lambda *a, **kw: None

  def _execute(self):
    force_interpreter = self._vars.PEX_INTERPRETER

    self.clean_environment()

    if force_interpreter:
      TRACER.log('PEX_INTERPRETER specified, dropping into interpreter')
      return self.execute_interpreter()

    if self._pex_info_overrides.script and self._pex_info_overrides.entry_point:
      die('Cannot specify both script and entry_point for a PEX!')

    if self._pex_info.script and self._pex_info.entry_point:
      die('Cannot specify both script and entry_point for a PEX!')

    if self._pex_info_overrides.script:
      return self.execute_script(self._pex_info_overrides.script)
    elif self._pex_info_overrides.entry_point:
      return self.execute_entry(self._pex_info_overrides.entry_point)
    elif self._pex_info.script:
      return self.execute_script(self._pex_info.script)
    elif self._pex_info.entry_point:
      return self.execute_entry(self._pex_info.entry_point)
    else:
      TRACER.log('No entry point specified, dropping into interpreter')
      return self.execute_interpreter()

  def execute_interpreter(self):
    if sys.argv[1:]:
      try:
        with open(sys.argv[1]) as fp:
          name, content = sys.argv[1], fp.read()
      except IOError as e:
        die("Could not open %s in the environment [%s]: %s" % (sys.argv[1], sys.argv[0], e))
      sys.argv = sys.argv[1:]
      self.execute_content(name, content)
    else:
      import code
      code.interact()

  def execute_script(self, script_name):
    dists = list(self._activate())

    entry_point = get_entry_point_from_console_script(script_name, dists)
    if entry_point:
      return self.execute_entry(entry_point)

    dist, script_path, script_content = get_script_from_distributions(script_name, dists)
    if not dist:
      raise self.NotFound('Could not find script %s in pex!' % script_name)
    TRACER.log('Found script %s in %s' % (script_name, dist))
    return self.execute_content(script_path, script_content, argv0=script_name)

  @classmethod
  def execute_content(cls, name, content, argv0=None):
    argv0 = argv0 or name
    try:
      ast = compile(content, name, 'exec', flags=0, dont_inherit=1)
    except SyntaxError:
      die('Unable to parse %s.  PEX script support only supports Python scripts.' % name)
    old_name, old_file = globals().get('__name__'), globals().get('__file__')
    try:
      old_argv0, sys.argv[0] = sys.argv[0], argv0
      globals()['__name__'] = '__main__'
      globals()['__file__'] = name
      exec_function(ast, globals())
    finally:
      if old_name:
        globals()['__name__'] = old_name
      else:
        globals().pop('__name__')
      if old_file:
        globals()['__file__'] = old_file
      else:
        globals().pop('__file__')
      sys.argv[0] = old_argv0

  @classmethod
  def execute_entry(cls, entry_point):
    runner = cls.execute_pkg_resources if ':' in entry_point else cls.execute_module
    runner(entry_point)

  @staticmethod
  def execute_module(module_name):
    import runpy
    runpy.run_module(module_name, run_name='__main__')

  @staticmethod
  def execute_pkg_resources(spec):
    entry = EntryPoint.parse("run = {0}".format(spec))

    # See https://pythonhosted.org/setuptools/history.html#id25 for rationale here.
    if hasattr(entry, 'resolve'):
      # setuptools >= 11.3
      runner = entry.resolve()
    else:
      # setuptools < 11.3
      runner = entry.load(require=False)
    runner()

  def cmdline(self, args=()):
    """The commandline to run this environment.

    :keyword args: Additional arguments to be passed to the application being invoked by the
      environment.
    """
    cmds = [self._interpreter.binary]
    cmds.append(self._pex)
    cmds.extend(args)
    return cmds

  def run(self, args=(), with_chroot=False, blocking=True, setsid=False, **kw):
    """Run the PythonEnvironment in an interpreter in a subprocess.

    :keyword args: Additional arguments to be passed to the application being invoked by the
      environment.
    :keyword with_chroot: Run with cwd set to the environment's working directory.
    :keyword blocking: If true, return the return code of the subprocess.
      If false, return the Popen object of the invoked subprocess.
    :keyword setsid: If true, run the PEX in a separate operating system session.

    Remaining keyword arguments are passed directly to subprocess.Popen.
    """
    self.clean_environment()

    cmdline = self.cmdline(args)
    TRACER.log('PEX.run invoking %s' % ' '.join(cmdline))
    process = subprocess.Popen(
        cmdline,
        cwd=self._pex if with_chroot else os.getcwd(),
        preexec_fn=os.setsid if setsid else None,
        **kw)
    return process.wait() if blocking else process
