import contextlib
import os
import random
import tempfile
from textwrap import dedent
import zipfile

from .common import safe_mkdir, safe_mkdtemp, safe_rmtree
from .installer import EggInstaller
from .util import DistributionHelper


@contextlib.contextmanager
def temporary_dir():
  td = tempfile.mkdtemp()
  try:
    yield td
  finally:
    safe_rmtree(td)


@contextlib.contextmanager
def create_layout(*filelist):
  with temporary_dir() as td:
    for fl in filelist:
      for fn in fl:
        with open(os.path.join(td, fn), 'w') as fp:
          fp.write('junk')
    yield td


def random_bytes(length):
  return ''.join(
      map(chr, (random.randint(ord('a'), ord('z')) for _ in range(length)))).encode('utf-8')


@contextlib.contextmanager
def temporary_content(content_map, interp=None, seed=31337):
  """Write content to disk where content is map from string => (int, string).

     If target is int, write int random bytes.  Otherwise write contents of string."""
  random.seed(seed)
  interp = interp or {}
  with temporary_dir() as td:
    for filename, size_or_content in content_map.items():
      safe_mkdir(os.path.dirname(os.path.join(td, filename)))
      with open(os.path.join(td, filename), 'wb') as fp:
        if isinstance(size_or_content, int):
          fp.write(random_bytes(size_or_content))
        else:
          fp.write((size_or_content % interp).encode('utf-8'))
    yield td


def yield_files(directory):
  for root, _, files in os.walk(directory):
    for f in files:
      filename = os.path.join(root, f)
      rel_filename = os.path.relpath(filename, directory)
      yield filename, rel_filename


def write_zipfile(directory, dest, reverse=False):
  with contextlib.closing(zipfile.ZipFile(dest, 'w')) as zf:
    for filename, rel_filename in sorted(yield_files(directory), reverse=reverse):
      zf.write(filename, arcname=rel_filename)
  return dest


PROJECT_CONTENT = {
  'setup.py': dedent('''
      from setuptools import setup

      setup(
          name=%(project_name)r,
          version='0.0.0',
          zip_safe=%(zip_safe)r,
          packages=['my_package'],
          package_data={'my_package': ['package_data/*.dat']},
      )
  '''),
  'my_package/__init__.py': 0,
  'my_package/my_module.py': 'def do_something():\n  print("hello world!")\n',
  'my_package/package_data/resource1.dat': 1000,
  'my_package/package_data/resource2.dat': 1000,
}


@contextlib.contextmanager
def make_distribution(name='my_project', installer_impl=EggInstaller, zipped=False, zip_safe=True):
  interp = {'project_name': name, 'zip_safe': zip_safe}
  with temporary_content(PROJECT_CONTENT, interp=interp) as td:
    installer = installer_impl(td)
    dist_location = installer.bdist()
    if zipped:
      yield DistributionHelper.distribution_from_path(dist_location)
    else:
      with temporary_dir() as td:
        extract_path = os.path.join(td, os.path.basename(dist_location))
        with contextlib.closing(zipfile.ZipFile(dist_location)) as zf:
          zf.extractall(extract_path)
        yield DistributionHelper.distribution_from_path(extract_path)
