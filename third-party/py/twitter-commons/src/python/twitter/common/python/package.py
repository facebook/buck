import contextlib
import os
import tarfile
import zipfile

from .base import maybe_requirement
from .common import safe_mkdtemp
from .http.link import Link
from .interpreter import PythonInterpreter
from .pep425 import PEP425, PEP425Extras
from .platforms import Platform

from pkg_resources import (
    EGG_NAME,
    parse_version,
    safe_name,
)


class Package(Link):
  """Base class for named Python binary packages (e.g. source, egg, wheel)."""

  # The registry of concrete implementations
  _REGISTRY = set()

  @classmethod
  def register(cls, package_type):
    """Register a concrete implementation of a Package to be recognized by twitter.common.python."""
    if not issubclass(package_type, cls):
      raise TypeError('package_type must be a subclass of Package.')
    cls._REGISTRY.add(package_type)

  @classmethod
  def from_href(cls, href, **kw):
    """Convert from a url to Package.

    :param href: The url to parse
    :type href: string
    :returns: A Package object if a valid concrete implementation exists, otherwise None.
    """
    for package_type in cls._REGISTRY:
      try:
        return package_type(href, **kw)
      except package_type.InvalidLink:
        continue

  @property
  def name(self):
    return NotImplementedError

  @property
  def raw_version(self):
    return NotImplementedError

  @property
  def version(self):
    return parse_version(self.raw_version)

  def satisfies(self, requirement):
    """Determine whether this package matches the requirement.

    :param requirement: The requirement to compare this Package against
    :type requirement: string or :class:`pkg_resources.Requirement`
    :returns: True if the package matches the requirement, otherwise False
    """
    requirement = maybe_requirement(requirement)
    link_name = safe_name(self.name).lower()
    if link_name != requirement.key:
      return False
    return self.raw_version in requirement

  def compatible(self, identity, platform=Platform.current()):
    """Is this link compatible with the given :class:`PythonIdentity` identity and platform?

    :param identity: The Python identity (e.g. CPython 2.7.5) against which compatibility
    should be checked.
    :type identity: :class:`PythonIdentity`
    :param platform: The platform against which compatibility should be checked.  If None, do not
    check platform compatibility.
    :type platform: string or None
    """
    raise NotImplementedError


class SourcePackage(Package):
  """A Package representing an uncompiled/unbuilt source distribution."""

  EXTENSIONS = {
    '.tar': (tarfile.TarFile.open, tarfile.ReadError),
    '.tar.gz': (tarfile.TarFile.open, tarfile.ReadError),
    '.tar.bz2': (tarfile.TarFile.open, tarfile.ReadError),
    '.tgz': (tarfile.TarFile.open, tarfile.ReadError),
    '.zip': (zipfile.ZipFile, zipfile.BadZipfile)
  }

  @classmethod
  def split_fragment(cls, fragment):
    """A heuristic used to split a string into version name/fragment:

       >>> split_fragment('pysolr-2.1.0-beta')
       ('pysolr', '2.1.0-beta')
       >>> split_fragment('cElementTree-1.0.5-20051216')
       ('cElementTree', '1.0.5-20051216')
       >>> split_fragment('pil-1.1.7b1-20090412')
       ('pil', '1.1.7b1-20090412')
       >>> split_fragment('django-plugin-2-2.3')
       ('django-plugin-2', '2.3')
    """
    def likely_version_component(enumerated_fragment):
      return sum(bool(v and v[0].isdigit()) for v in enumerated_fragment[1].split('.'))
    fragments = fragment.split('-')
    if len(fragments) == 1:
      return fragment, ''
    max_index, _ = max(enumerate(fragments), key=likely_version_component)
    return '-'.join(fragments[0:max_index]), '-'.join(fragments[max_index:])

  def __init__(self, url, **kw):
    super(SourcePackage, self).__init__(url, **kw)

    for ext, class_info in self.EXTENSIONS.items():
      if self.filename.endswith(ext):
        self._archive_class = class_info
        fragment = self.filename[:-len(ext)]
        break
    else:
      raise self.InvalidLink('%s does not end with any of: %s' % (
          self.filename, ' '.join(self.EXTENSIONS)))
    self._name, self._raw_version = self.split_fragment(fragment)

  @property
  def name(self):
    return safe_name(self._name)

  @property
  def raw_version(self):
    return safe_name(self._raw_version)

  @classmethod
  def first_nontrivial_dir(cls, path):
    files = os.listdir(path)
    if len(files) == 1 and os.path.isdir(os.path.join(path, files[0])):
      return cls.first_nontrivial_dir(os.path.join(path, files[0]))
    else:
      return path

  def _unpack(self, filename, location=None):
    path = location or safe_mkdtemp()
    archive_class, error_class = self._archive_class
    try:
      with contextlib.closing(archive_class(filename)) as package:
        package.extractall(path=path)
    except error_class:
      raise self.UnreadableLink('Could not read %s' % self.url)
    return self.first_nontrivial_dir(path)

  def fetch(self, location=None, conn_timeout=None):
    """Fetch and unpack this source target into the location.

    :param location: The location into which the archive should be unpacked.  If None, a temporary
    ephemeral directory will be created.
    :type location: string or None
    :param conn_timeout: A connection timeout for the fetch.  If None, a default is used.
    :type conn_timeout: float or None
    :returns: The assumed root directory of the package.
    """
    target = super(SourcePackage, self).fetch(conn_timeout=conn_timeout)
    return self._unpack(target, location)

  # SourcePackages are always compatible as they can be translated to a distribution.
  def compatible(self, identity, platform=Platform.current()):
    return True


class EggPackage(Package):
  """A Package representing a built egg."""

  def __init__(self, url, **kw):
    super(EggPackage, self).__init__(url, **kw)
    filename, ext = os.path.splitext(self.filename)
    if ext.lower() != '.egg':
      raise self.InvalidLink('Not an egg: %s' % filename)
    matcher = EGG_NAME(filename)
    if not matcher:
      raise self.InvalidLink('Could not match egg: %s' % filename)

    self._name, self._raw_version, self._py_version, self._platform = matcher.group(
        'name', 'ver', 'pyver', 'plat')

    if self._raw_version is None or self._py_version is None:
      raise self.InvalidLink('url with .egg extension but bad name: %s' % url)

  def __hash__(self):
    return hash((self.name, self.version, self.py_version, self.platform))

  @property
  def name(self):
    return safe_name(self._name)

  @property
  def raw_version(self):
    return safe_name(self._raw_version)

  @property
  def py_version(self):
    return self._py_version

  @property
  def platform(self):
    return self._platform

  def compatible(self, identity, platform=Platform.current()):
    if not Platform.version_compatible(self.py_version, identity.python):
      return False
    if not Platform.compatible(self.platform, platform):
      return False
    return True


class WheelPackage(Package):
  """A Package representing a built wheel."""

  def __init__(self, url, **kw):
    super(WheelPackage, self).__init__(url, **kw)
    filename, ext = os.path.splitext(self.filename)
    if ext.lower() != '.whl':
      raise self.InvalidLink('Not a wheel: %s' % filename)
    try:
      self._name, self._raw_version, self._py_tag, self._abi_tag, self._arch_tag = (
          filename.split('-'))
    except ValueError:
      raise self.InvalidLink('Wheel filename malformed.')
    # See https://github.com/pypa/pip/issues/1150 for why this is unavoidable.
    self._name.replace('_', '-')
    self._raw_version.replace('_', '-')
    self._supported_tags = frozenset(self._iter_tags())

  @property
  def name(self):
    return self._name

  @property
  def raw_version(self):
    return self._raw_version

  def _iter_tags(self):
    for py in self._py_tag.split('.'):
      for abi in self._abi_tag.split('.'):
        for arch in self._arch_tag.split('.'):
          for real_arch in PEP425Extras.platform_iterator(arch):
            yield (py, abi, real_arch)

  def compatible(self, identity, platform=Platform.current()):
    for tag in PEP425.iter_supported_tags(identity, platform):
      if tag in self._supported_tags:
        return True
    return False


Package.register(SourcePackage)
Package.register(EggPackage)
Package.register(WheelPackage)


def distribution_compatible(dist, interpreter=None, platform=None):
  """Is this distribution compatible with the given interpreter/platform combination?

  :param interpreter: The Python interpreter against which compatibility should be checked.  If None
  specified, the current interpreter is used.
  :type identity: :class:`PythonInterpreter` or None
  :param platform: The platform against which compatibility should be checked.  If None, the current
  platform will be used
  :type platform: string or None
  :returns: True if the distribution is compatible, False if it is unrecognized or incompatible.
  """
  interpreter = interpreter or PythonInterpreter.get()
  platform = platform or Platform.current()

  package = Package.from_href(dist.location)
  if not package:
    return False
  return package.compatible(interpreter.identity, platform=platform)
