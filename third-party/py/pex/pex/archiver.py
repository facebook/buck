# Copyright 2015 Pants project contributors (see CONTRIBUTORS.md).
# Licensed under the Apache License, Version 2.0 (see LICENSE).

import contextlib
import os
import tarfile
import zipfile

from .common import safe_mkdtemp


class Archiver(object):
  class Error(Exception): pass
  class UnpackError(Error): pass
  class InvalidArchive(Error): pass

  EXTENSIONS = {
    '.tar': (tarfile.TarFile.open, tarfile.ReadError),
    '.tar.gz': (tarfile.TarFile.open, tarfile.ReadError),
    '.tar.bz2': (tarfile.TarFile.open, tarfile.ReadError),
    '.tgz': (tarfile.TarFile.open, tarfile.ReadError),
    '.zip': (zipfile.ZipFile, zipfile.BadZipfile)
  }

  @classmethod
  def first_nontrivial_dir(cls, path):
    files = os.listdir(path)
    if len(files) == 1 and os.path.isdir(os.path.join(path, files[0])):
      return cls.first_nontrivial_dir(os.path.join(path, files[0]))
    else:
      return path

  @classmethod
  def get_extension(cls, filename):
    for ext in cls.EXTENSIONS:
      if filename.endswith(ext):
        return ext

  @classmethod
  def unpack(cls, filename, location=None):
    path = location or safe_mkdtemp()
    ext = cls.get_extension(filename)
    if ext is None:
      raise cls.InvalidArchive('Unknown archive format: %s' % filename)
    archive_class, error_class = cls.EXTENSIONS[ext]
    try:
      with contextlib.closing(archive_class(filename)) as package:
        package.extractall(path=path)
    except error_class:
      raise cls.UnpackError('Could not extract %s' % filename)
    return cls.first_nontrivial_dir(path)
