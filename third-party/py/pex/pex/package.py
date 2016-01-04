# Copyright 2014 Pants project contributors (see CONTRIBUTORS.md).
# Licensed under the Apache License, Version 2.0 (see LICENSE).

import os

from pkg_resources import EGG_NAME, parse_version, safe_name

from .archiver import Archiver
from .base import maybe_requirement
from .interpreter import PythonInterpreter
from .link import Link
from .pep425 import PEP425, PEP425Extras
from .platforms import Platform
from .util import Memoizer


class Package(Link):
  """Base class for named Python binary packages (e.g. source, egg, wheel)."""

  class Error(Exception): pass
  class InvalidPackage(Error): pass

  # The registry of concrete implementations
  _REGISTRY = set()

  # The cache of packages that we have already constructed.
  _HREF_TO_PACKAGE_CACHE = Memoizer()

  @classmethod
  def register(cls, package_type):
    """Register a concrete implementation of a Package to be recognized by pex."""
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
    package = cls._HREF_TO_PACKAGE_CACHE.get(href)
    if package is not None:
      return package
    link_href = Link.wrap(href)
    for package_type in cls._REGISTRY:
      try:
        package = package_type(link_href.url, **kw)
        break
      except package_type.InvalidPackage:
        continue
    if package is not None:
      cls._HREF_TO_PACKAGE_CACHE.store(href, package)
    return package

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

  @classmethod
  def split_fragment(cls, fragment):
    """A heuristic used to split a string into version name/fragment:

       >>> SourcePackage.split_fragment('pysolr-2.1.0-beta')
       ('pysolr', '2.1.0-beta')
       >>> SourcePackage.split_fragment('cElementTree-1.0.5-20051216')
       ('cElementTree', '1.0.5-20051216')
       >>> SourcePackage.split_fragment('pil-1.1.7b1-20090412')
       ('pil', '1.1.7b1-20090412')
       >>> SourcePackage.split_fragment('django-plugin-2-2.3')
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

    ext = Archiver.get_extension(self.filename)
    if ext is None:
      raise self.InvalidPackage('%s is not a recognized archive format.' % self.filename)

    fragment = self.filename[:-len(ext)]
    self._name, self._raw_version = self.split_fragment(fragment)

  @property
  def name(self):
    return safe_name(self._name)

  @property
  def raw_version(self):
    return safe_name(self._raw_version)

  # SourcePackages are always compatible as they can be translated to a distribution.
  def compatible(self, identity, platform=Platform.current()):
    return True


class EggPackage(Package):
  """A Package representing a built egg."""

  def __init__(self, url, **kw):
    super(EggPackage, self).__init__(url, **kw)
    filename, ext = os.path.splitext(self.filename)
    if ext.lower() != '.egg':
      raise self.InvalidPackage('Not an egg: %s' % filename)
    matcher = EGG_NAME(filename)
    if not matcher:
      raise self.InvalidPackage('Could not match egg: %s' % filename)

    self._name, self._raw_version, self._py_version, self._platform = matcher.group(
        'name', 'ver', 'pyver', 'plat')

    if self._raw_version is None or self._py_version is None:
      raise self.InvalidPackage('url with .egg extension but bad name: %s' % url)

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
      raise self.InvalidPackage('Not a wheel: %s' % filename)
    try:
      self._name, self._raw_version, self._py_tag, self._abi_tag, self._arch_tag = (
          filename.split('-'))
    except ValueError:
      raise self.InvalidPackage('Wheel filename malformed.')
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
