# Copyright 2014 Pants project contributors (see CONTRIBUTORS.md).
# Licensed under the Apache License, Version 2.0 (see LICENSE).

from __future__ import absolute_import

import contextlib
import errno
import os
import shutil
import uuid
from hashlib import sha1
from threading import Lock

from pkg_resources import find_distributions, resource_isdir, resource_listdir, resource_string

from .common import safe_mkdir, safe_mkdtemp, safe_open, safe_rmtree
from .finders import register_finders


class DistributionHelper(object):
  @classmethod
  def walk_data(cls, dist, path='/'):
    """Yields filename, stream for files identified as data in the distribution"""
    for rel_fn in filter(None, dist.resource_listdir(path)):
      full_fn = os.path.join(path, rel_fn)
      if dist.resource_isdir(full_fn):
        for fn, stream in cls.walk_data(dist, full_fn):
          yield fn, stream
      else:
        yield full_fn[1:], dist.get_resource_stream(dist._provider, full_fn)

  @staticmethod
  def zipsafe(dist):
    """Returns whether or not we determine a distribution is zip-safe."""
    # zip-safety is only an attribute of eggs.  wheels are considered never
    # zip safe per implications of PEP 427.
    if hasattr(dist, 'egg_info') and dist.egg_info.endswith('EGG-INFO'):
      egg_metadata = dist.metadata_listdir('')
      return 'zip-safe' in egg_metadata and 'native_libs.txt' not in egg_metadata
    else:
      return False

  @classmethod
  def access_zipped_assets(cls, static_module_name, static_path, dir_location=None):
    """
    Create a copy of static resource files as we can't serve them from within the pex file.

    :param static_module_name: Module name containing module to cache in a tempdir
    :type static_module_name: string, for example 'twitter.common.zookeeper' or similar
    :param static_path: Module name, for example 'serverset'
    :param dir_location: create a new temporary directory inside, or None to have one created
    :returns temp_dir: Temporary directory with the zipped assets inside
    :rtype: str
    """

    # asset_path is initially a module name that's the same as the static_path, but will be
    # changed to walk the directory tree
    def walk_zipped_assets(static_module_name, static_path, asset_path, temp_dir):
      for asset in resource_listdir(static_module_name, asset_path):
        asset_target = os.path.normpath(
            os.path.join(os.path.relpath(asset_path, static_path), asset))
        if resource_isdir(static_module_name, os.path.join(asset_path, asset)):
          safe_mkdir(os.path.join(temp_dir, asset_target))
          walk_zipped_assets(static_module_name, static_path, os.path.join(asset_path, asset),
            temp_dir)
        else:
          with open(os.path.join(temp_dir, asset_target), 'wb') as fp:
            path = os.path.join(static_path, asset_target)
            file_data = resource_string(static_module_name, path)
            fp.write(file_data)

    if dir_location is None:
      temp_dir = safe_mkdtemp()
    else:
      temp_dir = dir_location

    walk_zipped_assets(static_module_name, static_path, static_path, temp_dir)

    return temp_dir

  @classmethod
  def distribution_from_path(cls, path, name=None):
    """Return a distribution from a path.

    If name is provided, find the distribution.  If none is found matching the name,
    return None.  If name is not provided and there is unambiguously a single
    distribution, return that distribution otherwise None.
    """
    # Monkeypatch pkg_resources finders should it not already be so.
    register_finders()
    if name is None:
      distributions = list(find_distributions(path))
      if len(distributions) == 1:
        return distributions[0]
    else:
      for dist in find_distributions(path):
        if dist.project_name == name:
          return dist


class CacheHelper(object):
  @classmethod
  def update_hash(cls, filelike, digest):
    """Update the digest of a single file in a memory-efficient manner."""
    block_size = digest.block_size * 1024
    for chunk in iter(lambda: filelike.read(block_size), b''):
      digest.update(chunk)

  @classmethod
  def hash(cls, path, digest=None, hasher=sha1):
    """Return the digest of a single file in a memory-efficient manner."""
    if digest is None:
      digest = hasher()
    with open(path, 'rb') as fh:
      cls.update_hash(fh, digest)
    return digest.hexdigest()

  @classmethod
  def _compute_hash(cls, names, stream_factory):
    digest = sha1()
    digest.update(''.join(names).encode('utf-8'))
    for name in names:
      with contextlib.closing(stream_factory(name)) as fp:
        cls.update_hash(fp, digest)
    return digest.hexdigest()

  @classmethod
  def zip_hash(cls, zf, prefix=''):
    """Return the hash of the contents of a zipfile, comparable with a cls.dir_hash."""
    prefix_length = len(prefix)
    names = sorted(name[prefix_length:] for name in zf.namelist()
        if name.startswith(prefix) and not name.endswith('.pyc') and not name.endswith('/'))
    def stream_factory(name):
      return zf.open(prefix + name)
    return cls._compute_hash(names, stream_factory)

  @classmethod
  def _iter_files(cls, directory):
    normpath = os.path.normpath(directory)
    for root, _, files in os.walk(normpath):
      for f in files:
        yield os.path.relpath(os.path.join(root, f), normpath)

  @classmethod
  def pex_hash(cls, d):
    """Return a reproducible hash of the contents of a directory."""
    names = sorted(f for f in cls._iter_files(d) if not (f.endswith('.pyc') or f.startswith('.')))
    def stream_factory(name):
      return open(os.path.join(d, name), 'rb')  # noqa: T802
    return cls._compute_hash(names, stream_factory)

  @classmethod
  def dir_hash(cls, d):
    """Return a reproducible hash of the contents of a directory."""
    names = sorted(f for f in cls._iter_files(d) if not f.endswith('.pyc'))
    def stream_factory(name):
      return open(os.path.join(d, name), 'rb')  # noqa: T802
    return cls._compute_hash(names, stream_factory)

  @classmethod
  def cache_distribution(cls, zf, source, target_dir):
    """Possibly cache an egg from within a zipfile into target_cache.

       Given a zipfile handle and a filename corresponding to an egg distribution within
       that zip, maybe write to the target cache and return a Distribution."""
    dependency_basename = os.path.basename(source)
    if not os.path.exists(target_dir):
      target_dir_tmp = target_dir + '.' + uuid.uuid4().hex
      for name in zf.namelist():
        if name.startswith(source) and not name.endswith('/'):
          # strip off prefix + '/'
          target_name = os.path.join(dependency_basename, name[len(source) + 1:])
          with contextlib.closing(zf.open(name)) as zi:
            with safe_open(os.path.join(target_dir_tmp, target_name), 'wb') as fp:
              shutil.copyfileobj(zi, fp)
      try:
        os.rename(target_dir_tmp, target_dir)
      except OSError as e:
        if e.errno == errno.ENOTEMPTY:
          safe_rmtree(target_dir_tmp)
        else:
          raise

    dist = DistributionHelper.distribution_from_path(target_dir)
    assert dist is not None, 'Failed to cache distribution %s' % source
    return dist


class Memoizer(object):
  """A thread safe class for memoizing the results of a computation."""

  def __init__(self):
    self._data = {}
    self._lock = Lock()

  def get(self, key, default=None):
    with self._lock:
      return self._data.get(key, default)

  def store(self, key, value):
    with self._lock:
      self._data[key] = value
