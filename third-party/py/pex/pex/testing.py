# Copyright 2014 Pants project contributors (see CONTRIBUTORS.md).
# Licensed under the Apache License, Version 2.0 (see LICENSE).

import contextlib
import os
import random
import subprocess
import tempfile
import zipfile
from textwrap import dedent

from .common import safe_mkdir, safe_rmtree
from .compatibility import nested
from .installer import EggInstaller, Packager
from .pex_builder import PEXBuilder
from .util import DistributionHelper


@contextlib.contextmanager
def temporary_dir():
  td = tempfile.mkdtemp()
  try:
    yield td
  finally:
    safe_rmtree(td)


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
          scripts=[
              'scripts/hello_world',
              'scripts/shell_script',
          ],
          package_data={'my_package': ['package_data/*.dat']},
          install_requires=%(install_requires)r,
      )
  '''),
  'scripts/hello_world': '#!/usr/bin/env python\nprint("hello world!")\n',
  'scripts/shell_script': '#!/usr/bin/env bash\necho hello world\n',
  'my_package/__init__.py': 0,
  'my_package/my_module.py': 'def do_something():\n  print("hello world!")\n',
  'my_package/package_data/resource1.dat': 1000,
  'my_package/package_data/resource2.dat': 1000,
}


@contextlib.contextmanager
def make_installer(name='my_project', installer_impl=EggInstaller, zip_safe=True,
                   install_reqs=None):
  interp = {'project_name': name, 'zip_safe': zip_safe, 'install_requires': install_reqs or []}
  with temporary_content(PROJECT_CONTENT, interp=interp) as td:
    yield installer_impl(td)


@contextlib.contextmanager
def make_source_dir(name='my_project', install_reqs=None):
  interp = {'project_name': name, 'zip_safe': True, 'install_requires': install_reqs or []}
  with temporary_content(PROJECT_CONTENT, interp=interp) as td:
    yield td


def make_sdist(name='my_project', zip_safe=True, install_reqs=None):
  with make_installer(name=name, installer_impl=Packager, zip_safe=zip_safe,
                      install_reqs=install_reqs) as packager:
    return packager.sdist()


@contextlib.contextmanager
def make_bdist(name='my_project', installer_impl=EggInstaller, zipped=False, zip_safe=True):
  with make_installer(name=name, installer_impl=installer_impl, zip_safe=zip_safe) as installer:
    dist_location = installer.bdist()
    if zipped:
      yield DistributionHelper.distribution_from_path(dist_location)
    else:
      with temporary_dir() as td:
        extract_path = os.path.join(td, os.path.basename(dist_location))
        with contextlib.closing(zipfile.ZipFile(dist_location)) as zf:
          zf.extractall(extract_path)
        yield DistributionHelper.distribution_from_path(extract_path)


COVERAGE_PREAMBLE = """
try:
  from coverage import coverage
  cov = coverage(auto_data=True, data_suffix=True)
  cov.start()
except ImportError:
  pass
"""


def write_simple_pex(td, exe_contents, dists=None, coverage=False):
  """Write a pex file that contains an executable entry point

  :param td: temporary directory path
  :param exe_contents: entry point python file
  :type exe_contents: string
  :param dists: distributions to include, typically sdists or bdists
  :param coverage: include coverage header
  """
  dists = dists or []

  with open(os.path.join(td, 'exe.py'), 'w') as fp:
    fp.write(exe_contents)

  pb = PEXBuilder(path=td, preamble=COVERAGE_PREAMBLE if coverage else None)

  for dist in dists:
    pb.add_egg(dist.location)

  pb.set_executable(os.path.join(td, 'exe.py'))
  pb.freeze()

  return pb


# TODO(wickman) Why not PEX.run?
def run_simple_pex(pex, args=(), env=None):
  po = subprocess.Popen(
      [pex] + list(args),
      stdout=subprocess.PIPE,
      stderr=subprocess.STDOUT,
      env=env)
  po.wait()
  return po.stdout.read(), po.returncode


def run_simple_pex_test(body, args=(), env=None, dists=None, coverage=False):
  with nested(temporary_dir(), temporary_dir()) as (td1, td2):
    pb = write_simple_pex(td1, body, dists=dists, coverage=coverage)
    pex = os.path.join(td2, 'app.pex')
    pb.build(pex)
    return run_simple_pex(pex, args=args, env=env)


def _iter_filter(data_dict):
  fragment = '/%s/_pex/' % PEXBuilder.BOOTSTRAP_DIR
  for filename, records in data_dict.items():
    try:
      bi = filename.index(fragment)
    except ValueError:
      continue
    # rewrite to look like root source
    yield ('pex/' + filename[bi + len():], records)


def combine_pex_coverage(coverage_file_iter):
  from coverage.data import CoverageData

  combined = CoverageData(basename='.coverage_combined')

  for filename in coverage_file_iter:
    cov = CoverageData(basename=filename)
    cov.read()
    combined.add_line_data(dict(_iter_filter(cov.line_data())))
    combined.add_arc_data(dict(_iter_filter(cov.arc_data())))

  combined.write()
  return combined.filename
