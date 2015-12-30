# Copyright 2014 Pants project contributors (see CONTRIBUTORS.md).
# Licensed under the Apache License, Version 2.0 (see LICENSE).

from __future__ import print_function

import atexit
import contextlib
import errno
import os
import shutil
import stat
import sys
import tempfile
import threading
import zipfile
from collections import defaultdict
from uuid import uuid4


def die(msg, exit_code=1):
  print(msg, file=sys.stderr)
  sys.exit(exit_code)


def safe_copy(source, dest, overwrite=False):
  def do_copy():
    temp_dest = dest + uuid4().hex
    shutil.copyfile(source, temp_dest)
    os.rename(temp_dest, dest)

  try:
    os.link(source, dest)
  except OSError as e:
    if e.errno == errno.EEXIST:
      # File already exists.  If overwrite=True, write otherwise skip.
      if overwrite:
        do_copy()
    elif e.errno == errno.EXDEV:
      # Hard link across devices, fall back on copying
      do_copy()
    else:
      raise


# See http://stackoverflow.com/questions/2572172/referencing-other-modules-in-atexit
class MktempTeardownRegistry(object):
  def __init__(self):
    self._registry = defaultdict(set)
    self._getpid = os.getpid
    self._lock = threading.RLock()
    self._exists = os.path.exists
    self._getenv = os.getenv
    self._rmtree = shutil.rmtree
    atexit.register(self.teardown)

  def __del__(self):
    self.teardown()

  def register(self, path):
    with self._lock:
      self._registry[self._getpid()].add(path)
    return path

  def teardown(self):
    for td in self._registry.pop(self._getpid(), []):
      if self._exists(td):
        self._rmtree(td)


_MKDTEMP_SINGLETON = MktempTeardownRegistry()


@contextlib.contextmanager
def open_zip(path, *args, **kwargs):
  """A contextmanager for zip files.  Passes through positional and kwargs to zipfile.ZipFile."""
  with contextlib.closing(zipfile.ZipFile(path, *args, **kwargs)) as zip:
    yield zip


def safe_mkdtemp(**kw):
  """Create a temporary directory that is cleaned up on process exit.

  Takes the same parameters as tempfile.mkdtemp.
  """
  # proper lock sanitation on fork [issue 6721] would be desirable here.
  return _MKDTEMP_SINGLETON.register(tempfile.mkdtemp(**kw))


def register_rmtree(directory):
  """Register an existing directory to be cleaned up at process exit."""
  return _MKDTEMP_SINGLETON.register(directory)


def safe_mkdir(directory, clean=False):
  """Safely create a directory.

  Ensures a directory is present.  If it's not there, it is created.  If it
  is, it's a no-op.  no-op.  If clean is True, ensures the directory is
  empty.
  """
  if clean:
    safe_rmtree(directory)
  try:
    os.makedirs(directory)
  except OSError as e:
    if e.errno != errno.EEXIST:
      raise


def safe_open(filename, *args, **kwargs):
  """Safely open a file.

  ``safe_open`` ensures that the directory components leading up the
  specified file have been created first.
  """
  safe_mkdir(os.path.dirname(filename))
  return open(filename, *args, **kwargs)  # noqa: T802


def safe_delete(filename):
  """Delete a file safely. If it's not present, no-op."""
  try:
    os.unlink(filename)
  except OSError as e:
    if e.errno != errno.ENOENT:
      raise


def safe_rmtree(directory):
  """Delete a directory if it's present. If it's not present, no-op."""
  if os.path.exists(directory):
    shutil.rmtree(directory, True)


def chmod_plus_x(path):
  """Equivalent of unix `chmod a+x path`"""
  path_mode = os.stat(path).st_mode
  path_mode &= int('777', 8)
  if path_mode & stat.S_IRUSR:
    path_mode |= stat.S_IXUSR
  if path_mode & stat.S_IRGRP:
    path_mode |= stat.S_IXGRP
  if path_mode & stat.S_IROTH:
    path_mode |= stat.S_IXOTH
  os.chmod(path, path_mode)


def chmod_plus_w(path):
  """Equivalent of unix `chmod +w path`"""
  path_mode = os.stat(path).st_mode
  path_mode &= int('777', 8)
  path_mode |= stat.S_IWRITE
  os.chmod(path, path_mode)


def touch(file, times=None):
  """Equivalent of unix `touch path`.

  :file The file to touch.
  :times Either a tuple of (atime, mtime) or else a single time to use for both.  If not
  specified both atime and mtime are updated to the current time.
  """
  if times:
    if len(times) > 2:
      raise ValueError('times must either be a tuple of (atime, mtime) or else a single time value '
                       'to use for both.')

    if len(times) == 1:
      times = (times, times)

  with safe_open(file, 'a'):
    os.utime(file, times)


class Chroot(object):
  """A chroot of files overlayed from one directory to another directory.

  Files may be tagged when added in order to keep track of multiple overlays
  in the chroot.
  """
  class Error(Exception): pass
  class ChrootTaggingException(Error):
    def __init__(self, filename, orig_tag, new_tag):
      super(Chroot.ChrootTaggingException, self).__init__(  # noqa: T800
        "Trying to add %s to fileset(%s) but already in fileset(%s)!" % (
          filename, new_tag, orig_tag))

  def __init__(self, chroot_base):
    """Create the chroot.

    :chroot_base Directory for the creation of the target chroot.
    """
    try:
      safe_mkdir(chroot_base)
    except OSError as e:
      raise self.ChrootException('Unable to create chroot in %s: %s' % (chroot_base, e))
    self.chroot = chroot_base
    self.filesets = defaultdict(set)

  def clone(self, into=None):
    """Clone this chroot.

    :keyword into: (optional) An optional destination directory to clone the
      Chroot into.  If not specified, a temporary directory will be created.

    .. versionchanged:: 0.8
      The temporary directory created when ``into`` is not specified is now garbage collected on
      interpreter exit.
    """
    into = into or safe_mkdtemp()
    new_chroot = Chroot(into)
    for label, fileset in self.filesets.items():
      for fn in fileset:
        new_chroot.link(os.path.join(self.chroot, fn), fn, label=label)
    return new_chroot

  def path(self):
    """The path of the chroot."""
    return self.chroot

  def _normalize(self, dst):
    dst = os.path.normpath(dst)
    if dst.startswith(os.sep) or dst.startswith('..'):
      raise self.Error('Destination path is not a relative path!')
    return dst

  def _check_tag(self, fn, label):
    for fs_label, fs in self.filesets.items():
      if fn in fs and fs_label != label:
        raise self.ChrootTaggingException(fn, fs_label, label)

  def _tag(self, fn, label):
    self._check_tag(fn, label)
    self.filesets[label].add(fn)

  def _ensure_parent(self, path):
    safe_mkdir(os.path.dirname(os.path.join(self.chroot, path)))

  def copy(self, src, dst, label=None):
    """Copy file ``src`` to ``chroot/dst`` with optional label.

    May raise anything shutil.copyfile can raise, e.g.
      IOError(Errno 21 'EISDIR')

    May raise ChrootTaggingException if dst is already in a fileset
    but with a different label.
    """
    dst = self._normalize(dst)
    self._tag(dst, label)
    self._ensure_parent(dst)
    shutil.copyfile(src, os.path.join(self.chroot, dst))

  def link(self, src, dst, label=None):
    """Hard link file from ``src`` to ``chroot/dst`` with optional label.

    May raise anything os.link can raise, e.g.
      IOError(Errno 21 'EISDIR')

    May raise ChrootTaggingException if dst is already in a fileset
    but with a different label.
    """
    dst = self._normalize(dst)
    self._tag(dst, label)
    self._ensure_parent(dst)
    abs_src = src
    abs_dst = os.path.join(self.chroot, dst)
    try:
      os.link(abs_src, abs_dst)
    except OSError as e:
      if e.errno == errno.EEXIST:
        # File already exists, skip XXX -- ensure target and dest are same?
        pass
      elif e.errno == errno.EXDEV:
        # Hard link across devices, fall back on copying
        shutil.copyfile(abs_src, abs_dst)
      else:
        raise

  def write(self, data, dst, label=None, mode='wb'):
    """Write data to ``chroot/dst`` with optional label.

    Has similar exceptional cases as ``Chroot.copy``
    """
    dst = self._normalize(dst)
    self._tag(dst, label)
    self._ensure_parent(dst)
    with open(os.path.join(self.chroot, dst), mode) as wp:
      wp.write(data)

  def touch(self, dst, label=None):
    """Perform 'touch' on ``chroot/dst`` with optional label.

    Has similar exceptional cases as Chroot.copy
    """
    dst = self._normalize(dst)
    self._tag(dst, label)
    touch(os.path.join(self.chroot, dst))

  def get(self, label):
    """Get all files labeled with ``label``"""
    return self.filesets.get(label, set())

  def files(self):
    """Get all files in the chroot."""
    all_files = set()
    for label in self.filesets:
      all_files.update(self.filesets[label])
    return all_files

  def labels(self):
    return self.filesets.keys()

  def __str__(self):
    return 'Chroot(%s {fs:%s})' % (self.chroot,
      ' '.join('%s' % foo for foo in self.filesets.keys()))

  def delete(self):
    shutil.rmtree(self.chroot)

  def zip(self, filename, mode='wb'):
    with contextlib.closing(zipfile.ZipFile(filename, mode)) as zf:
      for f in sorted(self.files()):
        zf.write(os.path.join(self.chroot, f), arcname=f, compress_type=zipfile.ZIP_DEFLATED)
