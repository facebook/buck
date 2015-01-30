# ==================================================================================================
# Copyright 2013 Twitter, Inc.
# --------------------------------------------------------------------------------------------------
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this work except in compliance with the License.
# You may obtain a copy of the License in the LICENSE file, or at:
#
#  http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
# ==================================================================================================

import atexit
from collections import defaultdict
import contextlib
import errno
import os
import shutil
import sys
import stat
import tempfile
import threading
import zipfile


# See http://stackoverflow.com/questions/2572172/referencing-other-modules-in-atexit
class MktempTeardownRegistry(object):
  def __init__(self):
    self._registry = defaultdict(set)
    self._getpid = os.getpid
    self._lock = threading.RLock()
    self._exists = os.path.exists
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
  """
    A with-context for zip files.  Passes through positional and kwargs to zipfile.ZipFile.
  """
  with contextlib.closing(zipfile.ZipFile(path, *args, **kwargs)) as zip:
    yield zip


def safe_mkdtemp(**kw):
  """
    Given the parameters to standard tempfile.mkdtemp, create a temporary directory
    that is cleaned up on process exit.
  """
  # proper lock sanitation on fork [issue 6721] would be desirable here.
  return _MKDTEMP_SINGLETON.register(tempfile.mkdtemp(**kw))


def register_rmtree(directory):
  """
    Register an existing directory to be cleaned up at process exit.
  """
  return _MKDTEMP_SINGLETON.register(directory)


def safe_mkdir(directory, clean=False):
  """
    Ensure a directory is present.  If it's not there, create it.  If it is,
    no-op. If clean is True, ensure the directory is empty.
  """
  if clean:
    safe_rmtree(directory)
  try:
    os.makedirs(directory)
  except OSError as e:
    if e.errno != errno.EEXIST:
      raise


def safe_open(filename, *args, **kwargs):
  """
    Open a file safely (ensuring that the directory components leading up to it
    have been created first.)
  """
  safe_mkdir(os.path.dirname(filename))
  return open(filename, *args, **kwargs)


def safe_delete(filename):
  """
    Delete a file safely. If it's not present, no-op.
  """
  try:
    os.unlink(filename)
  except OSError as e:
    if e.errno != errno.ENOENT:
      raise


def safe_rmtree(directory):
  """
    Delete a directory if it's present. If it's not present, no-op.
  """
  if os.path.exists(directory):
    shutil.rmtree(directory, True)


def chmod_plus_x(path):
  """
    Equivalent of unix `chmod a+x path`
  """
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
  """
    Equivalent of unix `chmod +w path`
  """
  path_mode = os.stat(path).st_mode
  path_mode &= int('777', 8)
  path_mode |= stat.S_IWRITE
  os.chmod(path, path_mode)


def touch(file, times=None):
  """
    Equivalent of unix `touch path`.

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
  """
    A chroot of files overlayed from one directory to another directory.

    Files may be tagged when added in order to keep track of multiple overlays in
    the chroot.
  """
  class ChrootException(Exception): pass

  class ChrootTaggingException(Exception):
    def __init__(self, filename, orig_tag, new_tag):
      Exception.__init__(self,
        "Trying to add %s to fileset(%s) but already in fileset(%s)!" % (
          filename, new_tag, orig_tag))

  def __init__(self, chroot_base, name=None):
    """
      chroot_base = directory for the creation of the target chroot.
      name = if specified, create the chroot in a temporary directory underneath
        chroot_base with 'name' as the prefix, otherwise create the chroot directly
        into chroot_base
    """
    self.root = None
    try:
      safe_mkdir(chroot_base)
    except:
      raise Chroot.ChrootException('Unable to create chroot in %s' % chroot_base)
    if name is not None:
      self.chroot = tempfile.mkdtemp(dir=chroot_base, prefix='%s.' % name)
    else:
      self.chroot = chroot_base
    self.filesets = {}

  def set_relative_root(self, root):
    """
      Make all source paths relative to this root path.
    """
    self.root = root

  def clone(self, into=None):
    into = into or tempfile.mkdtemp()
    new_chroot = Chroot(into)
    new_chroot.root = self.root
    for label, fileset in self.filesets.items():
      for fn in fileset:
        new_chroot.link(os.path.join(self.chroot, self.root or '', fn),
                        fn, label=label)
    return new_chroot

  def path(self):
    """The path of the chroot."""
    return self.chroot

  def _check_tag(self, fn, label):
    for fs_label, fs in self.filesets.items():
      if fn in fs and fs_label != label:
        raise Chroot.ChrootTaggingException(fn, fs_label, label)

  def _tag(self, fn, label):
    self._check_tag(fn, label)
    if label not in self.filesets:
      self.filesets[label] = set()
    self.filesets[label].add(fn)

  def _mkdir_for(self, path):
    dirname = os.path.dirname(os.path.join(self.chroot, path))
    safe_mkdir(dirname)

  def _rootjoin(self, path):
    return os.path.join(self.root or '', path)

  def copy(self, src, dst, label=None):
    """
      Copy file from {root}/source to {chroot}/dest with optional label.

      May raise anything shutil.copyfile can raise, e.g.
        IOError(Errno 21 'EISDIR')

      May raise ChrootTaggingException if dst is already in a fileset
      but with a different label.
    """
    self._tag(dst, label)
    self._mkdir_for(dst)
    shutil.copyfile(self._rootjoin(src), os.path.join(self.chroot, dst))

  def link(self, src, dst, label=None):
    """
      Hard link file from {root}/source to {chroot}/dest with optional label.

      May raise anything os.link can raise, e.g.
        IOError(Errno 21 'EISDIR')

      May raise ChrootTaggingException if dst is already in a fileset
      but with a different label.
    """
    self._tag(dst, label)
    self._mkdir_for(dst)
    abs_src = self._rootjoin(src)
    abs_dst = os.path.join(self.chroot, dst)
    if hasattr(os, 'link'):
      try:
        os.link(abs_src, abs_dst)
      except OSError as e:
        if e.errno == errno.EEXIST:
          # File already exists, skip
          pass
        elif e.errno == errno.EXDEV:
          # Hard link across devices, fall back on copying
          shutil.copyfile(abs_src, abs_dst)
        else:
          raise
    else:
      # Python for Windows doesn't support hardlinks; fall back on copying
      if os.path.normcase(os.path.abspath(abs_src)) != os.path.normcase(os.path.abspath(abs_dst)):
        shutil.copyfile(abs_src, abs_dst)

  def write(self, data, dst, label=None, mode='wb'):
    """
      Write data to {chroot}/dest with optional label.

      Has similar exceptional cases as Chroot.copy
    """

    self._tag(dst, label)
    self._mkdir_for(dst)
    with open(os.path.join(self.chroot, dst), mode) as wp:
      wp.write(data)

  def touch(self, dst, label=None):
    """
      Perform 'touch' on {chroot}/dest with optional label.

      Has similar exceptional cases as Chroot.copy
    """
    self.write('', dst, label, mode='a')

  def get(self, label):
    """Get all files labeled with 'label'"""
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
