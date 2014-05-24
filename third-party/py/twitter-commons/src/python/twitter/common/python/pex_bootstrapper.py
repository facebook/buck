import contextlib
import os
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


# TODO(user) Remove once resolved:
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


def bootstrap_pex(entry_point):
  from .finders import register_finders
  monkeypatch_build_zipmanifest()
  register_finders()

  from . import pex
  pex.PEX(entry_point).execute()
