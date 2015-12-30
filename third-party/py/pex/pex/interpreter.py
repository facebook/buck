# Copyright 2014 Pants project contributors (see CONTRIBUTORS.md).
# Licensed under the Apache License, Version 2.0 (see LICENSE).

"""pex support for interacting with interpreters."""

from __future__ import absolute_import

import os
import re
import subprocess
import sys
from collections import defaultdict

from pkg_resources import Distribution, Requirement, find_distributions

from .base import maybe_requirement
from .compatibility import string
from .tracer import TRACER

try:
  from numbers import Integral
except ImportError:
  Integral = (int, long)


# Determine in the most platform-compatible way possible the identity of the interpreter
# and its known packages.
ID_PY = b"""
import sys

if hasattr(sys, 'pypy_version_info'):
  subversion = 'PyPy'
elif sys.platform.startswith('java'):
  subversion = 'Jython'
else:
  subversion = 'CPython'

print("%s %s %s %s" % (
  subversion,
  sys.version_info[0],
  sys.version_info[1],
  sys.version_info[2]))

setuptools_path = None
try:
  import pkg_resources
except ImportError:
  sys.exit(0)

requirements = {}
for item in sys.path:
  for dist in pkg_resources.find_distributions(item):
    requirements[str(dist.as_requirement())] = dist.location

for requirement_str, location in requirements.items():
  rs = requirement_str.split('==', 2)
  if len(rs) == 2:
    print('%s %s %s' % (rs[0], rs[1], location))
"""


class PythonIdentity(object):
  class Error(Exception): pass
  class InvalidError(Error): pass
  class UnknownRequirement(Error): pass

  # TODO(wickman)  Support interpreter-specific versions, e.g. PyPy-2.2.1
  HASHBANGS = {
    'CPython': 'python%(major)d.%(minor)d',
    'Jython': 'jython',
    'PyPy': 'pypy',
  }

  @classmethod
  def get_subversion(cls):
    if hasattr(sys, 'pypy_version_info'):
      subversion = 'PyPy'
    elif sys.platform.startswith('java'):
      subversion = 'Jython'
    else:
      subversion = 'CPython'
    return subversion

  @classmethod
  def get(cls):
    return cls(cls.get_subversion(), sys.version_info[0], sys.version_info[1], sys.version_info[2])

  @classmethod
  def from_id_string(cls, id_string):
    values = id_string.split()
    if len(values) != 4:
      raise cls.InvalidError("Invalid id string: %s" % id_string)
    return cls(str(values[0]), int(values[1]), int(values[2]), int(values[3]))

  @classmethod
  def from_path(cls, dirname):
    interp, version = dirname.split('-')
    major, minor, patch = version.split('.')
    return cls(str(interp), int(major), int(minor), int(patch))

  def __init__(self, interpreter, major, minor, patch):
    for var in (major, minor, patch):
      assert isinstance(var, Integral)
    self._interpreter = interpreter
    self._version = (major, minor, patch)

  @property
  def interpreter(self):
    return self._interpreter

  @property
  def version(self):
    return self._version

  @property
  def requirement(self):
    return self.distribution.as_requirement()

  @property
  def distribution(self):
    return Distribution(project_name=self._interpreter, version='.'.join(map(str, self._version)))

  @classmethod
  def parse_requirement(cls, requirement, default_interpreter='CPython'):
    if isinstance(requirement, Requirement):
      return requirement
    elif isinstance(requirement, string):
      try:
        requirement = Requirement.parse(requirement)
      except ValueError:
        try:
          requirement = Requirement.parse('%s%s' % (default_interpreter, requirement))
        except ValueError:
          raise ValueError('Unknown requirement string: %s' % requirement)
      return requirement
    else:
      raise ValueError('Unknown requirement type: %r' % (requirement,))

  def matches(self, requirement):
    """Given a Requirement, check if this interpreter matches."""
    try:
      requirement = self.parse_requirement(requirement, self._interpreter)
    except ValueError as e:
      raise self.UnknownRequirement(str(e))
    return self.distribution in requirement

  def hashbang(self):
    hashbang_string = self.HASHBANGS.get(self.interpreter, 'CPython') % {
      'major': self._version[0],
      'minor': self._version[1],
      'patch': self._version[2],
    }
    return '#!/usr/bin/env %s' % hashbang_string

  @property
  def python(self):
    # return the python version in the format of the 'python' key for distributions
    # specifically, '2.6', '2.7', '3.2', etc.
    return '%d.%d' % (self.version[0:2])

  def __str__(self):
    return '%s-%s.%s.%s' % (self._interpreter,
      self._version[0], self._version[1], self._version[2])

  def __repr__(self):
    return 'PythonIdentity(%r, %s, %s, %s)' % (
        self._interpreter, self._version[0], self._version[1], self._version[2])

  def __eq__(self, other):
    return all([isinstance(other, PythonIdentity),
                self.interpreter == other.interpreter,
                self.version == other.version])

  def __hash__(self):
    return hash((self._interpreter, self._version))


class PythonInterpreter(object):
  REGEXEN = (
    re.compile(r'jython$'),

    # NB: OSX ships python binaries named Python so we allow for capital-P.
    re.compile(r'[Pp]ython$'),

    re.compile(r'python[23].[0-9]$'),
    re.compile(r'pypy$'),
    re.compile(r'pypy-1.[0-9]$'),
  )

  CACHE = {}  # memoize executable => PythonInterpreter

  try:
    # Versions of distribute prior to the setuptools merge would automatically replace
    # 'setuptools' requirements with 'distribute'.  It provided the 'replacement' kwarg
    # to toggle this, but it was removed post-merge.
    COMPATIBLE_SETUPTOOLS = Requirement.parse('setuptools>=1.0', replacement=False)
  except TypeError:
    COMPATIBLE_SETUPTOOLS = Requirement.parse('setuptools>=1.0')

  class Error(Exception): pass
  class IdentificationError(Error): pass
  class InterpreterNotFound(Error): pass

  @classmethod
  def get(cls):
    return cls.from_binary(sys.executable)

  @classmethod
  def all(cls, paths=None):
    if paths is None:
      paths = os.getenv('PATH', '').split(':')
    return cls.filter(cls.find(paths))

  @classmethod
  def _parse_extras(cls, output_lines):
    def iter_lines():
      for line in output_lines:
        try:
          dist_name, dist_version, location = line.split()
        except ValueError:
          raise cls.IdentificationError('Could not identify requirement: %s' % line)
        yield ((dist_name, dist_version), location)
    return dict(iter_lines())

  @classmethod
  def _from_binary_internal(cls, path_extras):
    def iter_extras():
      for item in sys.path + list(path_extras):
        for dist in find_distributions(item):
          if dist.version:
            yield ((dist.key, dist.version), dist.location)
    return cls(sys.executable, PythonIdentity.get(), dict(iter_extras()))

  @classmethod
  def _from_binary_external(cls, binary, path_extras):
    environ = cls.sanitized_environment()
    environ['PYTHONPATH'] = ':'.join(path_extras)
    po = subprocess.Popen(
        [binary],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        env=environ)
    so, _ = po.communicate(ID_PY)
    output = so.decode('utf8').splitlines()
    if len(output) == 0:
      raise cls.IdentificationError('Could not establish identity of %s' % binary)
    identity, extras = output[0], output[1:]
    return cls(
        binary,
        PythonIdentity.from_id_string(identity),
        extras=cls._parse_extras(extras))

  @classmethod
  def expand_path(cls, path):
    if os.path.isfile(path):
      return [path]
    elif os.path.isdir(path):
      return [os.path.join(path, fn) for fn in os.listdir(path)]
    return []

  @classmethod
  def from_env(cls, hashbang):
    """Resolve a PythonInterpreter as /usr/bin/env would.

       :param hashbang: A string, e.g. "python3.3" representing some binary on the $PATH.
    """
    paths = os.getenv('PATH', '').split(':')
    for path in paths:
      for fn in cls.expand_path(path):
        basefile = os.path.basename(fn)
        if hashbang == basefile:
          try:
            return cls.from_binary(fn)
          except Exception as e:
            TRACER.log('Could not identify %s: %s' % (fn, e))

  @classmethod
  def from_binary(cls, binary, path_extras=None):
    path_extras = path_extras or ()
    if binary not in cls.CACHE:
      if binary == sys.executable:
        cls.CACHE[binary] = cls._from_binary_internal(path_extras)
      else:
        cls.CACHE[binary] = cls._from_binary_external(binary, path_extras)
    return cls.CACHE[binary]

  @classmethod
  def find(cls, paths):
    """
      Given a list of files or directories, try to detect python interpreters amongst them.
      Returns a list of PythonInterpreter objects.
    """
    pythons = []
    for path in paths:
      for fn in cls.expand_path(path):
        basefile = os.path.basename(fn)
        if any(matcher.match(basefile) is not None for matcher in cls.REGEXEN):
          try:
            pythons.append(cls.from_binary(fn))
          except Exception as e:
            TRACER.log('Could not identify %s: %s' % (fn, e))
            continue
    return pythons

  @classmethod
  def filter(cls, pythons):
    """
      Given a map of python interpreters in the format provided by PythonInterpreter.find(),
      filter out duplicate versions and versions we would prefer not to use.

      Returns a map in the same format as find.
    """
    good = []

    MAJOR, MINOR, SUBMINOR = range(3)
    def version_filter(version):
      return (version[MAJOR] == 2 and version[MINOR] >= 6 or
              version[MAJOR] == 3 and version[MINOR] >= 2)

    all_versions = set(interpreter.identity.version for interpreter in pythons)
    good_versions = filter(version_filter, all_versions)

    for version in good_versions:
      # For each candidate, use the latest version we find on the filesystem.
      candidates = defaultdict(list)
      for interp in pythons:
        if interp.identity.version == version:
          candidates[interp.identity.interpreter].append(interp)
      for interp_class in candidates:
        candidates[interp_class].sort(
            key=lambda interp: os.path.getmtime(interp.binary), reverse=True)
        good.append(candidates[interp_class].pop(0))

    return good

  @classmethod
  def sanitized_environment(cls):
    # N.B. This is merely a hack because sysconfig.py on the default OS X
    # installation of 2.6/2.7 breaks.
    env_copy = os.environ.copy()
    env_copy.pop('MACOSX_DEPLOYMENT_TARGET', None)
    return env_copy

  @classmethod
  def replace(cls, requirement):
    self = cls.get()
    if self.identity.matches(requirement):
      return False
    for pi in cls.all():
      if pi.identity.matches(requirement):
        break
    else:
      raise cls.InterpreterNotFound('Could not find interpreter matching filter!')
    os.execve(pi.binary, [pi.binary] + sys.argv, cls.sanitized_environment())

  def __init__(self, binary, identity, extras=None):
    """Construct a PythonInterpreter.

       You should probably PythonInterpreter.from_binary instead.

       :param binary: The full path of the python binary.
       :param identity: The :class:`PythonIdentity` of the PythonInterpreter.
       :param extras: A mapping from (dist.key, dist.version) to dist.location
                      of the extras associated with this interpreter.
    """
    self._binary = os.path.realpath(binary)
    self._extras = extras or {}
    self._identity = identity

  def with_extra(self, key, version, location):
    extras = self._extras.copy()
    extras[(key, version)] = location
    return self.__class__(self._binary, self._identity, extras)

  @property
  def extras(self):
    return self._extras.copy()

  @property
  def binary(self):
    return self._binary

  @property
  def identity(self):
    return self._identity

  @property
  def python(self):
    return self._identity.python

  @property
  def version(self):
    return self._identity.version

  @property
  def version_string(self):
    return str(self._identity)

  def satisfies(self, capability):
    if not isinstance(capability, list):
      raise TypeError('Capability must be a list, got %s' % type(capability))
    return not any(self.get_location(req) is None for req in capability)

  def get_location(self, req):
    req = maybe_requirement(req)
    for dist, location in self.extras.items():
      dist_name, dist_version = dist
      if req.key == dist_name and dist_version in req:
        return location

  def __hash__(self):
    return hash((self._binary, self._identity))

  def __eq__(self, other):
    if not isinstance(other, PythonInterpreter):
      return False
    return (self._binary, self._identity) == (other._binary, other._identity)

  def __lt__(self, other):
    if not isinstance(other, PythonInterpreter):
      return False
    return self.version < other.version

  def __repr__(self):
    return '%s(%r, %r, %r)' % (self.__class__.__name__, self._binary, self._identity, self._extras)
