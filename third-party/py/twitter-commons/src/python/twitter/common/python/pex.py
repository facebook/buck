from __future__ import absolute_import, print_function

from contextlib import contextmanager
from distutils import sysconfig
import os
from site import USER_SITE
import sys
import traceback

from .common import safe_mkdir
from .compatibility import exec_function
from .environment import PEXEnvironment
from .interpreter import PythonInterpreter
from .orderedset import OrderedSet
from .pex_info import PexInfo
from .tracer import Tracer

import pkg_resources
from pkg_resources import EntryPoint, find_distributions


TRACER = Tracer(predicate=Tracer.env_filter('PEX_VERBOSE'), prefix='twitter.common.python.pex: ')


class DevNull(object):
  def __init__(self):
    pass

  def write(self, *args, **kw):
    pass


class PEX(object):
  """
    PEX, n. A self-contained python environment.
  """
  class Error(Exception): pass
  class NotFound(Error): pass

  @staticmethod
  def start_coverage():
    try:
      import coverage
      cov = coverage.coverage(auto_data=True, data_suffix=True,
        data_file='.coverage.%s' % os.environ['PEX_COVERAGE'])
      cov.start()
    except ImportError:
      sys.stderr.write('Could not bootstrap coverage module!\n')

  @classmethod
  def clean_environment(cls, forking=False):
    os.unsetenv('MACOSX_DEPLOYMENT_TARGET')
    if not forking:
      for key in filter(lambda key: key.startswith('PEX_'), os.environ):
        os.unsetenv(key)

  def __init__(self, pex=sys.argv[0], interpreter=None):
    self._pex = pex
    self._pex_info = PexInfo.from_pex(self._pex)
    self._env = PEXEnvironment(self._pex, self._pex_info)
    self._interpreter = interpreter or PythonInterpreter.get()

  @property
  def info(self):
    return self._pex_info

  def entry(self):
    """
      Return the module spec of the entry point of this PEX.  None if there
      is no entry point for this environment.
    """
    if 'PEX_MODULE' in os.environ:
      TRACER.log('PEX_MODULE override detected: %s' % os.environ['PEX_MODULE'])
      return os.environ['PEX_MODULE']
    entry_point = self._pex_info.entry_point
    if entry_point:
      TRACER.log('Using prescribed entry point: %s' % entry_point)
      return str(entry_point)

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
    return site_libs

  @classmethod
  def minimum_sys_modules(cls, site_libs):
    new_modules = {}

    for module_name, module in sys.modules.items():
      if any(path.startswith(site_lib) for path in getattr(module, '__path__', ())
          for site_lib in site_libs):
        TRACER.log('Scrubbing %s from sys.modules' % module)
      else:
        new_modules[module_name] = module

    return new_modules

  @classmethod
  def minimum_sys_path(cls, site_libs):
    site_distributions = OrderedSet()
    for path_element in sys.path:
      if any(path_element.startswith(site_lib) for site_lib in site_libs):
        TRACER.log('Inspecting path element: %s' % path_element, V=2)
        site_distributions.update(dist.location for dist in find_distributions(path_element))

    user_site_distributions = OrderedSet(dist.location for dist in find_distributions(USER_SITE))

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

    sys_modules = cls.minimum_sys_modules(site_libs)
    sys_path, sys_path_importer_cache = cls.minimum_sys_path(site_libs)

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

    try:
      yield
    finally:
      patch_all(old_sys_path, old_sys_path_importer_cache, old_sys_modules)

  def execute(self, args=()):
    """Execute the PEX.

    This function makes assumptions that it is the last function called by
    the interpreter.
    """

    entry_point = self.entry()

    try:
      with self.patch_sys():
        working_set = self._env.activate()
        if 'PEX_COVERAGE' in os.environ:
          PEX.start_coverage()
        TRACER.log('PYTHONPATH contains:')
        for element in sys.path:
          TRACER.log('  %c %s' % (' ' if os.path.exists(element) else '*', element))
        TRACER.log('  * - paths that do not exist or will be imported via zipimport')
        with self.patch_pkg_resources(working_set):
          if entry_point and 'PEX_INTERPRETER' not in os.environ:
            self.execute_entry(entry_point, args)
          else:
            self.execute_interpreter()
    except Exception:
      # Catch and print any exceptions before we tear things down in finally, then
      # reraise so that the exit status is reflected correctly.
      traceback.print_exc()
      raise
    finally:
      # squash all exceptions on interpreter teardown -- the primary type here are
      # atexit handlers failing to run because of things such as:
      #   http://stackoverflow.com/questions/2572172/referencing-other-modules-in-atexit
      if 'PEX_TEARDOWN_VERBOSE' not in os.environ:
        sys.stderr = DevNull()
        sys.excepthook = lambda *a, **kw: None

  @classmethod
  def execute_interpreter(cls):
    force_interpreter = 'PEX_INTERPRETER' in os.environ
    # TODO(user) Apparently os.unsetenv doesn't work on Windows
    os.unsetenv('PEX_INTERPRETER')
    TRACER.log('%s, dropping into interpreter' % (
        'PEX_INTERPRETER specified' if force_interpreter else 'No entry point specified'))
    if sys.argv[1:]:
      try:
        with open(sys.argv[1]) as fp:
          ast = compile(fp.read(), fp.name, 'exec', flags=0, dont_inherit=1)
      except IOError as e:
        print("Could not open %s in the environment [%s]: %s" % (sys.argv[1], sys.argv[0], e))
        sys.exit(1)
      sys.argv = sys.argv[1:]
      old_name = globals()['__name__']
      try:
        globals()['__name__'] = '__main__'
        exec_function(ast, globals())
      finally:
        globals()['__name__'] = old_name
    else:
      import code
      code.interact()

  @classmethod
  def execute_entry(cls, entry_point, args=None):
    if args:
      sys.argv = args
    runner = cls.execute_pkg_resources if ":" in entry_point else cls.execute_module

    if 'PEX_PROFILE' not in os.environ:
      runner(entry_point)
    else:
      import pstats, cProfile
      profile_output = os.environ['PEX_PROFILE']
      safe_mkdir(os.path.dirname(profile_output))
      cProfile.runctx('runner(entry_point)', globals=globals(), locals=locals(),
                      filename=profile_output)
      try:
        entries = int(os.environ.get('PEX_PROFILE_ENTRIES', 1000))
      except ValueError:
        entries = 1000
      pstats.Stats(profile_output).sort_stats(
          os.environ.get('PEX_PROFILE_SORT', 'cumulative')).print_stats(entries)

  @staticmethod
  def execute_module(module_name):
    import runpy
    runpy.run_module(module_name, run_name='__main__')

  @staticmethod
  def execute_pkg_resources(spec):
    entry = EntryPoint.parse("run = {0}".format(spec))
    runner = entry.load(require=False)  # trust that the environment is sane
    runner()

  def cmdline(self, args=()):
    """
      The commandline to run this environment.

      Optional arguments:
        binary: The binary to run instead of the entry point in the environment
        interpreter_args: Arguments to be passed to the interpreter before, e.g. '-E' or
          ['-m', 'pylint.lint']
        args: Arguments to be passed to the application being invoked by the environment.
    """
    cmds = [self._interpreter.binary]
    cmds.append(self._pex)
    cmds.extend(args)
    return cmds

  def run(self, args=(), with_chroot=False, blocking=True, setsid=False,
          stdin=sys.stdin, stdout=sys.stdout, stderr=sys.stderr):
    """
      Run the PythonEnvironment in an interpreter in a subprocess.

      with_chroot: Run with cwd set to the environment's working directory [default: False]
      blocking: If true, return the return code of the subprocess.
                If false, return the Popen object of the invoked subprocess.
    """
    import subprocess
    self.clean_environment(forking=True)

    cmdline = self.cmdline(args)
    TRACER.log('PEX.run invoking %s' % ' '.join(cmdline))
    process = subprocess.Popen(cmdline, cwd=self._pex if with_chroot else os.getcwd(),
                               preexec_fn=os.setsid if setsid else None,
                               stdin=stdin, stdout=stdout, stderr=stderr)
    return process.wait() if blocking else process
