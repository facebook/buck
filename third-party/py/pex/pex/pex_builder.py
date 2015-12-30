# Copyright 2014 Pants project contributors (see CONTRIBUTORS.md).
# Licensed under the Apache License, Version 2.0 (see LICENSE).

from __future__ import absolute_import

import logging
import os

from pkg_resources import DefaultProvider, ZipProvider, get_provider

from .common import Chroot, chmod_plus_x, open_zip, safe_mkdir, safe_mkdtemp
from .compatibility import to_bytes
from .compiler import Compiler
from .finders import get_entry_point_from_console_script, get_script_from_distributions
from .interpreter import PythonInterpreter
from .pex_info import PexInfo
from .util import CacheHelper, DistributionHelper


BOOTSTRAP_ENVIRONMENT = b"""
import os
import sys

__entry_point__ = None
if '__file__' in locals() and __file__ is not None:
  __entry_point__ = os.path.dirname(__file__)
elif '__loader__' in locals():
  from zipimport import zipimporter
  from pkgutil import ImpLoader
  if hasattr(__loader__, 'archive'):
    __entry_point__ = __loader__.archive
  elif isinstance(__loader__, ImpLoader):
    __entry_point__ = os.path.dirname(__loader__.get_filename())

if __entry_point__ is None:
  sys.stderr.write('Could not launch python executable!\\n')
  sys.exit(2)

sys.path[0] = os.path.abspath(sys.path[0])
sys.path.insert(0, os.path.abspath(os.path.join(__entry_point__, '.bootstrap')))

from _pex.pex_bootstrapper import bootstrap_pex
bootstrap_pex(__entry_point__)
"""


class PEXBuilder(object):
  """Helper for building PEX environments."""

  class Error(Exception): pass
  class ImmutablePEX(Error): pass
  class InvalidDistribution(Error): pass
  class InvalidDependency(Error): pass
  class InvalidExecutableSpecification(Error): pass

  BOOTSTRAP_DIR = ".bootstrap"

  def __init__(self, path=None, interpreter=None, chroot=None, pex_info=None, preamble=None,
               copy=False):
    """Initialize a pex builder.

    :keyword path: The path to write the PEX as it is built.  If ``None`` is specified,
      a temporary directory will be created.
    :keyword interpreter: The interpreter to use to build this PEX environment.  If ``None``
      is specified, the current interpreter is used.
    :keyword chroot: If specified, preexisting :class:`Chroot` to use for building the PEX.
    :keyword pex_info: A preexisting PexInfo to use to build the PEX.
    :keyword preamble: If supplied, execute this code prior to bootstrapping this PEX
      environment.
    :type preamble: str
    :keyword copy: If False, attempt to create the pex environment via hard-linking, falling
                   back to copying across devices. If True, always copy.

    .. versionchanged:: 0.8
      The temporary directory created when ``path`` is not specified is now garbage collected on
      interpreter exit.
    """
    self._chroot = chroot or Chroot(path or safe_mkdtemp())
    self._pex_info = pex_info or PexInfo.default()
    self._frozen = False
    self._interpreter = interpreter or PythonInterpreter.get()
    self._shebang = self._interpreter.identity.hashbang()
    self._logger = logging.getLogger(__name__)
    self._preamble = to_bytes(preamble or '')
    self._copy = copy
    self._distributions = set()

  def _ensure_unfrozen(self, name='Operation'):
    if self._frozen:
      raise self.ImmutablePEX('%s is not allowed on a frozen PEX!' % name)

  @property
  def interpreter(self):
    return self._interpreter

  def chroot(self):
    return self._chroot

  def clone(self, into=None):
    """Clone this PEX environment into a new PEXBuilder.

    :keyword into: (optional) An optional destination directory to clone this PEXBuilder into.  If
      not specified, a temporary directory will be created.

    Clones PEXBuilder into a new location.  This is useful if the PEXBuilder has been frozen and
    rendered immutable.

    .. versionchanged:: 0.8
      The temporary directory created when ``into`` is not specified is now garbage collected on
      interpreter exit.
    """
    chroot_clone = self._chroot.clone(into=into)
    clone = self.__class__(
        chroot=chroot_clone,
        interpreter=self._interpreter,
        pex_info=self._pex_info.copy())
    for dist in self._distributions:
      clone.add_distribution(dist)
    return clone

  def path(self):
    return self.chroot().path()

  @property
  def info(self):
    return self._pex_info

  @info.setter
  def info(self, value):
    if not isinstance(value, PexInfo):
      raise TypeError('PEXBuilder.info must be a PexInfo!')
    self._ensure_unfrozen('Changing PexInfo')
    self._pex_info = value

  def add_source(self, filename, env_filename):
    """Add a source to the PEX environment.

    :param filename: The source filename to add to the PEX.
    :param env_filename: The destination filename in the PEX.  This path
      must be a relative path.
    """
    self._ensure_unfrozen('Adding source')
    self._copy_or_link(filename, env_filename, "source")

  def add_resource(self, filename, env_filename):
    """Add a resource to the PEX environment.

    :param filename: The source filename to add to the PEX.
    :param env_filename: The destination filename in the PEX.  This path
      must be a relative path.
    """
    self._ensure_unfrozen('Adding a resource')
    self._copy_or_link(filename, env_filename, "resource")

  def add_requirement(self, req):
    """Add a requirement to the PEX environment.

    :param req: A requirement that should be resolved in this environment.

    .. versionchanged:: 0.8
      Removed ``dynamic`` and ``repo`` keyword arguments as they were unused.
    """
    self._ensure_unfrozen('Adding a requirement')
    self._pex_info.add_requirement(req)

  def set_executable(self, filename, env_filename=None):
    """Set the executable for this environment.

    :param filename: The file that should be executed within the PEX environment when the PEX is
      invoked.
    :keyword env_filename: (optional) The name that the executable file should be stored as within
      the PEX.  By default this will be the base name of the given filename.

    The entry point of the PEX may also be specified via ``PEXBuilder.set_entry_point``.
    """
    self._ensure_unfrozen('Setting the executable')
    if self._pex_info.script:
      raise self.InvalidExecutableSpecification('Cannot set both entry point and script of PEX!')
    if env_filename is None:
      env_filename = os.path.basename(filename)
    if self._chroot.get("executable"):
      raise self.InvalidExecutableSpecification(
          "Setting executable on a PEXBuilder that already has one!")
    self._copy_or_link(filename, env_filename, "executable")
    entry_point = env_filename
    entry_point.replace(os.path.sep, '.')
    self._pex_info.entry_point = entry_point.rpartition('.')[0]

  def set_script(self, script):
    """Set the entry point of this PEX environment based upon a distribution script.

    :param script: The script name as defined either by a console script or ordinary
      script within the setup.py of one of the distributions added to the PEX.
    :raises: :class:`PEXBuilder.InvalidExecutableSpecification` if the script is not found
      in any distribution added to the PEX.
    """

    # check if 'script' is a console_script
    entry_point = get_entry_point_from_console_script(script, self._distributions)
    if entry_point:
      self.set_entry_point(entry_point)
      return

    # check if 'script' is an ordinary script
    script_path, _, _ = get_script_from_distributions(script, self._distributions)
    if script_path:
      if self._pex_info.entry_point:
        raise self.InvalidExecutableSpecification('Cannot set both entry point and script of PEX!')
      self._pex_info.script = script
      return

    raise self.InvalidExecutableSpecification(
        'Could not find script %r in any distribution %s within PEX!' % (
            script, ', '.join(self._distributions)))

  def set_entry_point(self, entry_point):
    """Set the entry point of this PEX environment.

    :param entry_point: The entry point of the PEX in the form of ``module`` or ``module:symbol``,
      or ``None``.
    :type entry_point: string or None

    By default the entry point is None.  The behavior of a ``None`` entry point is dropping into
    an interpreter.  If ``module``, it will be executed via ``runpy.run_module``.  If
    ``module:symbol``, it is equivalent to ``from module import symbol; symbol()``.

    The entry point may also be specified via ``PEXBuilder.set_executable``.
    """
    self._ensure_unfrozen('Setting an entry point')
    self._pex_info.entry_point = entry_point

  def set_shebang(self, shebang):
    """Set the exact shebang line for the PEX file.

    For example, pex_builder.set_shebang('/home/wickman/Local/bin/python3.4').  This is
    used to override the default behavior which is to have a #!/usr/bin/env line referencing an
    interpreter compatible with the one used to build the PEX.

    :param shebang: The shebang line minus the #!.
    :type shebang: str
    """
    self._shebang = '#!%s' % shebang

  def _add_dist_dir(self, path, dist_name):
    for root, _, files in os.walk(path):
      for f in files:
        filename = os.path.join(root, f)
        relpath = os.path.relpath(filename, path)
        target = os.path.join(self._pex_info.internal_cache, dist_name, relpath)
        self._copy_or_link(filename, target)
    return CacheHelper.dir_hash(path)

  def _add_dist_zip(self, path, dist_name):
    with open_zip(path) as zf:
      for name in zf.namelist():
        if name.endswith('/'):
          continue
        target = os.path.join(self._pex_info.internal_cache, dist_name, name)
        self._chroot.write(zf.read(name), target)
      return CacheHelper.zip_hash(zf)

  def _prepare_code_hash(self):
    self._pex_info.code_hash = CacheHelper.pex_hash(self._chroot.path())

  def add_distribution(self, dist, dist_name=None):
    """Add a :class:`pkg_resources.Distribution` from its handle.

    :param dist: The distribution to add to this environment.
    :keyword dist_name: (optional) The name of the distribution e.g. 'Flask-0.10.0'.  By default
      this will be inferred from the distribution itself should it be formatted in a standard way.
    :type dist: :class:`pkg_resources.Distribution`
    """
    self._ensure_unfrozen('Adding a distribution')
    dist_name = dist_name or os.path.basename(dist.location)
    self._distributions.add(dist)

    if os.path.isdir(dist.location):
      dist_hash = self._add_dist_dir(dist.location, dist_name)
    else:
      dist_hash = self._add_dist_zip(dist.location, dist_name)

    # add dependency key so that it can rapidly be retrieved from cache
    self._pex_info.add_distribution(dist_name, dist_hash)

  def add_dist_location(self, dist, name=None):
    """Add a distribution by its location on disk.

    :param dist: The path to the distribution to add.
    :keyword name: (optional) The name of the distribution, should the dist directory alone be
      ambiguous.  Packages contained within site-packages directories may require specifying
      ``name``.
    :raises PEXBuilder.InvalidDistribution: When the path does not contain a matching distribution.

    PEX supports packed and unpacked .whl and .egg distributions, as well as any distribution
    supported by setuptools/pkg_resources.
    """
    self._ensure_unfrozen('Adding a distribution')
    bdist = DistributionHelper.distribution_from_path(dist)
    if bdist is None:
      raise self.InvalidDistribution('Could not find distribution at %s' % dist)
    self.add_distribution(bdist)
    self.add_requirement(bdist.as_requirement())

  def add_egg(self, egg):
    """Alias for add_dist_location."""
    self._ensure_unfrozen('Adding an egg')
    return self.add_dist_location(egg)

  # TODO(wickman) Consider changing this behavior to put the onus on the consumer
  # of pex to write the pex sources correctly.
  def _prepare_inits(self):
    relative_digest = self._chroot.get("source")
    init_digest = set()
    for path in relative_digest:
      split_path = path.split(os.path.sep)
      for k in range(1, len(split_path)):
        sub_path = os.path.sep.join(split_path[0:k] + ['__init__.py'])
        if sub_path not in relative_digest and sub_path not in init_digest:
          import_string = "__import__('pkg_resources').declare_namespace(__name__)"
          try:
            self._chroot.write(import_string, sub_path)
          except TypeError:
            # Python 3
            self._chroot.write(bytes(import_string, 'UTF-8'), sub_path)
          init_digest.add(sub_path)

  def _precompile_source(self):
    source_relpaths = [path for label in ('source', 'executable', 'main', 'bootstrap')
                       for path in self._chroot.filesets.get(label, ()) if path.endswith('.py')]

    compiler = Compiler(self.interpreter)
    compiled_relpaths = compiler.compile(self._chroot.path(), source_relpaths)
    for compiled in compiled_relpaths:
      self._chroot.touch(compiled, label='bytecode')

  def _prepare_manifest(self):
    self._chroot.write(self._pex_info.dump().encode('utf-8'), PexInfo.PATH, label='manifest')

  def _prepare_main(self):
    self._chroot.write(self._preamble + b'\n' + BOOTSTRAP_ENVIRONMENT,
        '__main__.py', label='main')

  def _copy_or_link(self, src, dst, label=None):
    if self._copy:
      self._chroot.copy(src, dst, label)
    else:
      self._chroot.link(src, dst, label)

  # TODO(wickman) Ideally we unqualify our setuptools dependency and inherit whatever is
  # bundled into the environment so long as it is compatible (and error out if not.)
  #
  # As it stands, we're picking and choosing the pieces we think we need, which means
  # if there are bits of setuptools imported from elsewhere they may be incompatible with
  # this.
  def _prepare_bootstrap(self):
    # Writes enough of setuptools into the .pex .bootstrap directory so that we can be fully
    # self-contained.

    wrote_setuptools = False
    setuptools = DistributionHelper.distribution_from_path(
        self._interpreter.get_location('setuptools'),
        name='setuptools')

    if setuptools is None:
      raise RuntimeError('Failed to find setuptools while building pex!')

    for fn, content_stream in DistributionHelper.walk_data(setuptools):
      if fn.startswith('pkg_resources') or fn.startswith('_markerlib'):
        if not fn.endswith('.pyc'):  # We'll compile our own .pyc's later.
          dst = os.path.join(self.BOOTSTRAP_DIR, fn)
          self._chroot.write(content_stream.read(), dst, 'bootstrap')
          wrote_setuptools = True

    if not wrote_setuptools:
      raise RuntimeError(
          'Failed to extract pkg_resources from setuptools.  Perhaps pants was linked with an '
          'incompatible setuptools.')

    libraries = {
      'pex': '_pex',
    }

    for source_name, target_location in libraries.items():
      provider = get_provider(source_name)
      if not isinstance(provider, DefaultProvider):
        mod = __import__(source_name, fromlist=['ignore'])
        provider = ZipProvider(mod)
      for fn in provider.resource_listdir(''):
        if fn.endswith('.py'):
          self._chroot.write(provider.get_resource_string(source_name, fn),
            os.path.join(self.BOOTSTRAP_DIR, target_location, fn), 'bootstrap')

  def freeze(self, bytecode_compile=True):
    """Freeze the PEX.

    :param bytecode_compile: If True, precompile .py files into .pyc files when freezing code.

    Freezing the PEX writes all the necessary metadata and environment bootstrapping code.  It may
    only be called once and renders the PEXBuilder immutable.
    """
    self._ensure_unfrozen('Freezing the environment')
    self._prepare_inits()
    self._prepare_code_hash()
    self._prepare_manifest()
    self._prepare_bootstrap()
    self._prepare_main()
    if bytecode_compile:
      self._precompile_source()
    self._frozen = True

  def build(self, filename, bytecode_compile=True):
    """Package the PEX into a zipfile.

    :param filename: The filename where the PEX should be stored.
    :param bytecode_compile: If True, precompile .py files into .pyc files.

    If the PEXBuilder is not yet frozen, it will be frozen by ``build``.  This renders the
    PEXBuilder immutable.
    """
    if not self._frozen:
      self.freeze(bytecode_compile=bytecode_compile)
    try:
      os.unlink(filename + '~')
      self._logger.warn('Previous binary unexpectedly exists, cleaning: %s' % (filename + '~'))
    except OSError:
      # The expectation is that the file does not exist, so continue
      pass
    if os.path.dirname(filename):
      safe_mkdir(os.path.dirname(filename))
    with open(filename + '~', 'ab') as pexfile:
      assert os.path.getsize(pexfile.name) == 0
      pexfile.write(to_bytes('%s\n' % self._shebang))
    self._chroot.zip(filename + '~', mode='a')
    if os.path.exists(filename):
      os.unlink(filename)
    os.rename(filename + '~', filename)
    chmod_plus_x(filename)
