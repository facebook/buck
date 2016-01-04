# Copyright 2014 Pants project contributors (see CONTRIBUTORS.md).
# Licensed under the Apache License, Version 2.0 (see LICENSE).

import contextlib
import os
import sys
import zipfile

__all__ = ('bootstrap_pex',)


def pex_info_name(entry_point):
  """Return the PEX-INFO for an entry_point"""
  return os.path.join(entry_point, 'PEX-INFO')


def is_compressed(entry_point):
  return os.path.exists(entry_point) and not os.path.exists(pex_info_name(entry_point))


def read_pexinfo_from_directory(entry_point):
  with open(pex_info_name(entry_point), 'rb') as fp:
    return fp.read()


def read_pexinfo_from_zip(entry_point):
  with contextlib.closing(zipfile.ZipFile(entry_point)) as zf:
    return zf.read('PEX-INFO')


def read_pex_info_content(entry_point):
  """Return the raw content of a PEX-INFO."""
  if is_compressed(entry_point):
    return read_pexinfo_from_zip(entry_point)
  else:
    return read_pexinfo_from_directory(entry_point)


def get_pex_info(entry_point):
  """Return the PexInfo object for an entry point."""
  from . import pex_info

  pex_info_content = read_pex_info_content(entry_point)
  if pex_info_content:
    return pex_info.PexInfo.from_json(pex_info_content)
  raise ValueError('Invalid entry_point: %s' % entry_point)


# TODO(wickman) Remove once resolved (#91):
#   https://bitbucket.org/pypa/setuptools/issue/154/build_zipmanifest-results-should-be
def monkeypatch_build_zipmanifest():
  import pkg_resources
  if not hasattr(pkg_resources, 'build_zipmanifest'):
    return
  old_build_zipmanifest = pkg_resources.build_zipmanifest
  def memoized_build_zipmanifest(archive, memo={}):
    if archive not in memo:
      memo[archive] = old_build_zipmanifest(archive)
    return memo[archive]
  pkg_resources.build_zipmanifest = memoized_build_zipmanifest


def find_in_path(target_interpreter):
  if os.path.exists(target_interpreter):
    return target_interpreter

  for directory in os.getenv('PATH', '').split(os.pathsep):
    try_path = os.path.join(directory, target_interpreter)
    if os.path.exists(try_path):
      return try_path


def maybe_reexec_pex():
  from .variables import ENV
  if not ENV.PEX_PYTHON:
    return

  from .common import die
  from .tracer import TRACER

  target_python = ENV.PEX_PYTHON
  target = find_in_path(target_python)
  if not target:
    die('Failed to find interpreter specified by PEX_PYTHON: %s' % target)
  if os.path.exists(target) and os.path.realpath(target) != os.path.realpath(sys.executable):
    TRACER.log('Detected PEX_PYTHON, re-exec to %s' % target)
    ENV.delete('PEX_PYTHON')
    os.execve(target, [target_python] + sys.argv, ENV.copy())


def bootstrap_pex(entry_point):
  from .finders import register_finders
  monkeypatch_build_zipmanifest()
  register_finders()
  maybe_reexec_pex()

  from . import pex
  pex.PEX(entry_point).execute()


def bootstrap_pex_env(entry_point):
  """Bootstrap the current runtime environment using a given pex."""
  from .environment import PEXEnvironment
  from .finders import register_finders
  from .pex_info import PexInfo

  monkeypatch_build_zipmanifest()
  register_finders()

  PEXEnvironment(entry_point, PexInfo.from_pex(entry_point)).activate()
