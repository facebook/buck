# Copyright 2014 Pants project contributors (see CONTRIBUTORS.md).
# Licensed under the Apache License, Version 2.0 (see LICENSE).

"""The finders we wish we had in setuptools.

As of setuptools 3.3, the only finder for zip-based distributions is for eggs.  The path-based
finder only searches paths ending in .egg and not in .whl (zipped or unzipped.)

pex.finders augments pkg_resources with additional finders to achieve functional
parity between wheels and eggs in terms of findability with find_distributions.

To use:
   >>> from pex.finders import register_finders
   >>> register_finders()
"""

import os
import pkgutil
import sys
import zipimport

import pkg_resources

if sys.version_info >= (3, 3) and sys.implementation.name == "cpython":
  import importlib._bootstrap as importlib_bootstrap
else:
  importlib_bootstrap = None


class ChainedFinder(object):
  """A utility to chain together multiple pkg_resources finders."""

  @classmethod
  def of(cls, *chained_finder_or_finder):
    finders = []
    for finder in chained_finder_or_finder:
      if isinstance(finder, cls):
        finders.extend(finder.finders)
      else:
        finders.append(finder)
    return cls(finders)

  def __init__(self, finders):
    self.finders = finders

  def __call__(self, importer, path_item, only=False):
    for finder in self.finders:
      for dist in finder(importer, path_item, only=only):
        yield dist

  def __eq__(self, other):
    if not isinstance(other, ChainedFinder):
      return False
    return self.finders == other.finders


# The following methods are somewhat dangerous as pkg_resources._distribution_finders is not an
# exposed API.  As it stands, pkg_resources doesn't provide an API to chain multiple distribution
# finders together.  This is probably possible using importlib but that does us no good as the
# importlib machinery supporting this is only available in Python >= 3.1.
def _get_finder(importer):
  if not hasattr(pkg_resources, '_distribution_finders'):
    return None
  return pkg_resources._distribution_finders.get(importer)


def _add_finder(importer, finder):
  """Register a new pkg_resources path finder that does not replace the existing finder."""

  existing_finder = _get_finder(importer)

  if not existing_finder:
    pkg_resources.register_finder(importer, finder)
  else:
    pkg_resources.register_finder(importer, ChainedFinder.of(existing_finder, finder))


def _remove_finder(importer, finder):
  """Remove an existing finder from pkg_resources."""

  existing_finder = _get_finder(importer)

  if not existing_finder:
    return

  if isinstance(existing_finder, ChainedFinder):
    try:
      existing_finder.finders.remove(finder)
    except ValueError:
      return
    if len(existing_finder.finders) == 1:
      pkg_resources.register_finder(importer, existing_finder.finders[0])
    elif len(existing_finder.finders) == 0:
      pkg_resources.register_finder(importer, pkg_resources.find_nothing)
  else:
    pkg_resources.register_finder(importer, pkg_resources.find_nothing)


class WheelMetadata(pkg_resources.EggMetadata):
  """Metadata provider for zipped wheels."""

  @classmethod
  def _split_wheelname(cls, wheelname):
    split_wheelname = wheelname.split('-')
    return '-'.join(split_wheelname[:-3])

  def _setup_prefix(self):
    path = self.module_path
    old = None
    while path != old:
      if path.lower().endswith('.whl'):
        self.egg_name = os.path.basename(path)
        # TODO(wickman) Test the regression where we have both upper and lower cased package
        # names.
        self.egg_info = os.path.join(path, '%s.dist-info' % self._split_wheelname(self.egg_name))
        self.egg_root = path
        break
      old = path
      path, base = os.path.split(path)


# See https://bitbucket.org/tarek/distribute/issue/274
class FixedEggMetadata(pkg_resources.EggMetadata):
  """An EggMetadata provider that has functional parity with the disk-based provider."""

  @classmethod
  def normalized_elements(cls, path):
    path_split = path.split('/')
    while path_split[-1] in ('', '.'):
      path_split.pop(-1)
    return path_split

  def _fn(self, base, resource_name):
    # super() does not work here as EggMetadata is an old-style class.
    original_fn = pkg_resources.EggMetadata._fn(self, base, resource_name)
    return '/'.join(self.normalized_elements(original_fn))

  def _zipinfo_name(self, fspath):
    fspath = self.normalized_elements(fspath)
    zip_pre = self.normalized_elements(self.zip_pre)
    if fspath[:len(zip_pre)] == zip_pre:
      return '/'.join(fspath[len(zip_pre):])
    assert "%s is not a subpath of %s" % (fspath, self.zip_pre)


def wheel_from_metadata(location, metadata):
  if not metadata.has_metadata(pkg_resources.DistInfoDistribution.PKG_INFO):
    return None

  from email.parser import Parser
  pkg_info = Parser().parsestr(metadata.get_metadata(pkg_resources.DistInfoDistribution.PKG_INFO))
  return pkg_resources.DistInfoDistribution(
      location=location,
      metadata=metadata,
      # TODO(wickman) Is this necessary or will they get picked up correctly?
      project_name=pkg_info.get('Name'),
      version=pkg_info.get('Version'),
      platform=None)


def find_wheels_on_path(importer, path_item, only=False):
  if not os.path.isdir(path_item) or not os.access(path_item, os.R_OK):
    return
  if not only:
    for entry in os.listdir(path_item):
      if entry.lower().endswith('.whl'):
        for dist in pkg_resources.find_distributions(os.path.join(path_item, entry)):
          yield dist


def find_eggs_in_zip(importer, path_item, only=False):
  if importer.archive.endswith('.whl'):
    # Defer to wheel importer
    return
  metadata = FixedEggMetadata(importer)
  if metadata.has_metadata('PKG-INFO'):
    yield pkg_resources.Distribution.from_filename(path_item, metadata=metadata)
  if only:
    return  # don't yield nested distros
  for subitem in metadata.resource_listdir('/'):
    if subitem.endswith('.egg'):
      subpath = os.path.join(path_item, subitem)
      for dist in find_eggs_in_zip(zipimport.zipimporter(subpath), subpath):
        yield dist


def find_wheels_in_zip(importer, path_item, only=False):
  metadata = WheelMetadata(importer)
  dist = wheel_from_metadata(path_item, metadata)
  if dist:
    yield dist


__PREVIOUS_FINDER = None


def register_finders():
  """Register finders necessary for PEX to function properly."""

  # If the previous finder is set, then we've already monkeypatched, so skip.
  global __PREVIOUS_FINDER
  if __PREVIOUS_FINDER:
    return

  # save previous finder so that it can be restored
  previous_finder = _get_finder(zipimport.zipimporter)
  assert previous_finder, 'This appears to be using an incompatible setuptools.'

  # replace the zip finder with our own implementation of find_eggs_in_zip which uses the correct
  # metadata handler, in addition to find_wheels_in_zip
  pkg_resources.register_finder(
      zipimport.zipimporter, ChainedFinder.of(find_eggs_in_zip, find_wheels_in_zip))

  # append the wheel finder
  _add_finder(pkgutil.ImpImporter, find_wheels_on_path)

  if importlib_bootstrap is not None:
    _add_finder(importlib_bootstrap.FileFinder, find_wheels_on_path)

  __PREVIOUS_FINDER = previous_finder


def unregister_finders():
  """Unregister finders necessary for PEX to function properly."""

  global __PREVIOUS_FINDER
  if not __PREVIOUS_FINDER:
    return

  pkg_resources.register_finder(zipimport.zipimporter, __PREVIOUS_FINDER)
  _remove_finder(pkgutil.ImpImporter, find_wheels_on_path)

  if importlib_bootstrap is not None:
    _remove_finder(importlib_bootstrap.FileFinder, find_wheels_on_path)

  __PREVIOUS_FINDER = None


def get_script_from_egg(name, dist):
  """Returns location, content of script in distribution or (None, None) if not there."""
  if name in dist.metadata_listdir('scripts'):
    return (
        os.path.join(dist.egg_info, 'scripts', name),
        dist.get_metadata('scripts/%s' % name).replace('\r\n', '\n').replace('\r', '\n'))
  return None, None


def safer_name(name):
  return name.replace('-', '_')


def get_script_from_whl(name, dist):
  # This is true as of at least wheel==0.24.  Might need to take into account the
  # metadata version bundled with the wheel.
  wheel_scripts_dir = '%s-%s.data/scripts' % (safer_name(dist.key), dist.version)
  if dist.resource_isdir(wheel_scripts_dir) and name in dist.resource_listdir(wheel_scripts_dir):
    script_path = os.path.join(wheel_scripts_dir, name)
    return (
        os.path.join(dist.egg_info, script_path),
        dist.get_resource_string('', script_path).replace(b'\r\n', b'\n').replace(b'\r', b'\n'))
  return None, None


def get_script_from_distribution(name, dist):
  # PathMetadata: exploded distribution on disk.
  if isinstance(dist._provider, pkg_resources.PathMetadata):
    if dist.egg_info.endswith('EGG-INFO'):
      return get_script_from_egg(name, dist)
    elif dist.egg_info.endswith('.dist-info'):
      return get_script_from_whl(name, dist)
    else:
      return None, None
  # FixedEggMetadata: Zipped egg
  elif isinstance(dist._provider, FixedEggMetadata):
    return get_script_from_egg(name, dist)
  # WheelMetadata: Zipped whl (in theory should not experience this at runtime.)
  elif isinstance(dist._provider, WheelMetadata):
    return get_script_from_whl(name, dist)
  return None, None


def get_script_from_distributions(name, dists):
  for dist in dists:
    script_path, script_content = get_script_from_distribution(name, dist)
    if script_path:
      return dist, script_path, script_content
  return None, None, None


def get_entry_point_from_console_script(script, dists):
  # check all distributions for the console_script "script"
  entries = frozenset(filter(None, (
      dist.get_entry_map().get('console_scripts', {}).get(script) for dist in dists)))

  # if multiple matches, freak out
  if len(entries) > 1:
    raise RuntimeError(
        'Ambiguous script specification %s matches multiple entry points:%s' % (
            script, ' '.join(map(str, entries))))

  if entries:
    entry_point = next(iter(entries))
    # entry points are of the form 'foo = bar', we just want the 'bar' part:
    return str(entry_point).split('=')[1].strip()
