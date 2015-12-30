# Copyright 2014 Pants project contributors (see CONTRIBUTORS.md).
# Licensed under the Apache License, Version 2.0 (see LICENSE).

from __future__ import absolute_import, print_function

import json
import os
import warnings
from collections import namedtuple

from .common import open_zip
from .compatibility import string as compatibility_string
from .compatibility import PY2
from .orderedset import OrderedSet
from .variables import ENV

PexPlatform = namedtuple('PexPlatform', 'interpreter version strict')


# TODO(wickman) Split this into a PexInfoBuilder/PexInfo to ensure immutability.
# Issue #92.
class PexInfo(object):
  """PEX metadata.

  # Build metadata:
  build_properties: BuildProperties # (key-value information about the build system)
  code_hash: str                    # sha1 hash of all names/code in the archive
  distributions: {dist_name: str}   # map from distribution name (i.e. path in
                                    # the internal cache) to its cache key (sha1)
  requirements: list                # list of requirements for this environment

  # Environment options
  pex_root: ~/.pex                   # root of all pex-related files
  entry_point: string                # entry point into this pex
  script: string                     # script to execute in this pex environment
                                     # at most one of script/entry_point can be specified
  zip_safe: True, default False      # is this pex zip safe?
  inherit_path: True, default False  # should this pex inherit site-packages + PYTHONPATH?
  ignore_errors: True, default False # should we ignore inability to resolve dependencies?
  always_write_cache: False          # should we always write the internal cache to disk first?
                                     # this is useful if you have very large dependencies that
                                     # do not fit in RAM constrained environments

  .. versionchanged:: 0.8
    Removed the ``repositories`` and ``indices`` information, as they were never
    implemented.
  """

  PATH = 'PEX-INFO'
  INTERNAL_CACHE = '.deps'

  @classmethod
  def make_build_properties(cls):
    from .interpreter import PythonInterpreter
    from pkg_resources import get_platform

    pi = PythonInterpreter.get()
    return {
      'class': pi.identity.interpreter,
      'version': pi.identity.version,
      'platform': get_platform(),
    }

  @classmethod
  def default(cls):
    pex_info = {
      'requirements': [],
      'distributions': {},
      'build_properties': cls.make_build_properties(),
    }
    return cls(info=pex_info)

  @classmethod
  def from_pex(cls, pex):
    if os.path.isfile(pex):
      with open_zip(pex) as zf:
        pex_info = zf.read(cls.PATH)
    else:
      with open(os.path.join(pex, cls.PATH)) as fp:
        pex_info = fp.read()
    return cls.from_json(pex_info)

  @classmethod
  def from_json(cls, content):
    if isinstance(content, bytes):
      content = content.decode('utf-8')
    return cls(info=json.loads(content))

  @classmethod
  def from_env(cls, env=ENV):
    supplied_env = env.strip_defaults()
    zip_safe = None if supplied_env.PEX_FORCE_LOCAL is None else not supplied_env.PEX_FORCE_LOCAL
    pex_info = {
      'pex_root': supplied_env.PEX_ROOT,
      'entry_point': supplied_env.PEX_MODULE,
      'script': supplied_env.PEX_SCRIPT,
      'zip_safe': zip_safe,
      'inherit_path': supplied_env.PEX_INHERIT_PATH,
      'ignore_errors': supplied_env.PEX_IGNORE_ERRORS,
      'always_write_cache': supplied_env.PEX_ALWAYS_CACHE,
    }
    # Filter out empty entries not explicitly set in the environment.
    return cls(info=dict((k, v) for (k, v) in pex_info.items() if v is not None))

  @classmethod
  def _parse_requirement_tuple(cls, requirement_tuple):
    if isinstance(requirement_tuple, (tuple, list)):
      if len(requirement_tuple) != 3:
        raise ValueError('Malformed PEX requirement: %r' % (requirement_tuple,))
      # pre 0.8.x requirement type:
      warnings.warn('Attempting to use deprecated PEX feature.  Please upgrade past PEX 0.8.x.')
      return requirement_tuple[0]
    elif isinstance(requirement_tuple, compatibility_string):
      return requirement_tuple
    raise ValueError('Malformed PEX requirement: %r' % (requirement_tuple,))

  def __init__(self, info=None):
    """Construct a new PexInfo.  This should not be used directly."""

    if info is not None and not isinstance(info, dict):
      raise ValueError('PexInfo can only be seeded with a dict, got: '
                       '%s of type %s' % (info, type(info)))
    self._pex_info = info or {}
    self._distributions = self._pex_info.get('distributions', {})
    requirements = self._pex_info.get('requirements', [])
    if not isinstance(requirements, (list, tuple)):
      raise ValueError('Expected requirements to be a list, got %s' % type(requirements))
    self._requirements = OrderedSet(self._parse_requirement_tuple(req) for req in requirements)

  def _get_safe(self, key):
    if key not in self._pex_info:
      return None
    value = self._pex_info[key]
    return value.encode('utf-8') if PY2 else value

  @property
  def build_properties(self):
    """Information about the system on which this PEX was generated.

    :returns: A dictionary containing metadata about the environment used to build this PEX.
    """
    return self._pex_info.get('build_properties', {})

  @build_properties.setter
  def build_properties(self, value):
    if not isinstance(value, dict):
      raise TypeError('build_properties must be a dictionary!')
    self._pex_info['build_properties'] = self.make_build_properties()
    self._pex_info['build_properties'].update(value)

  @property
  def zip_safe(self):
    """Whether or not this PEX should be treated as zip-safe.

    If set to false and the PEX is zipped, the contents of the PEX will be unpacked into a
    directory within the PEX_ROOT prior to execution.  This allows code and frameworks depending
    upon __file__ existing on disk to operate normally.

    By default zip_safe is True.  May be overridden at runtime by the $PEX_FORCE_LOCAL environment
    variable.
    """
    return self._pex_info.get('zip_safe', True)

  @zip_safe.setter
  def zip_safe(self, value):
    self._pex_info['zip_safe'] = bool(value)

  @property
  def inherit_path(self):
    """Whether or not this PEX should be allowed to inherit system dependencies.

    By default, PEX environments are scrubbed of all system distributions prior to execution.
    This means that PEX files cannot rely upon preexisting system libraries.

    By default inherit_path is False.  This may be overridden at runtime by the $PEX_INHERIT_PATH
    environment variable.
    """
    return self._pex_info.get('inherit_path', False)

  @inherit_path.setter
  def inherit_path(self, value):
    self._pex_info['inherit_path'] = bool(value)

  @property
  def ignore_errors(self):
    return self._pex_info.get('ignore_errors', False)

  @ignore_errors.setter
  def ignore_errors(self, value):
    self._pex_info['ignore_errors'] = bool(value)

  @property
  def code_hash(self):
    return self._pex_info.get('code_hash')

  @code_hash.setter
  def code_hash(self, value):
    self._pex_info['code_hash'] = value

  @property
  def entry_point(self):
    return self._get_safe('entry_point')

  @entry_point.setter
  def entry_point(self, value):
    self._pex_info['entry_point'] = value

  @property
  def script(self):
    return self._get_safe('script')

  @script.setter
  def script(self, value):
    self._pex_info['script'] = value

  def add_requirement(self, requirement):
    self._requirements.add(str(requirement))

  @property
  def requirements(self):
    return self._requirements

  def add_distribution(self, location, sha):
    self._distributions[location] = sha

  @property
  def distributions(self):
    return self._distributions

  @property
  def always_write_cache(self):
    return self._pex_info.get('always_write_cache', False)

  @always_write_cache.setter
  def always_write_cache(self, value):
    self._pex_info['always_write_cache'] = bool(value)

  @property
  def pex_root(self):
    return os.path.expanduser(self._pex_info.get('pex_root', os.path.join('~', '.pex')))

  @pex_root.setter
  def pex_root(self, value):
    self._pex_info['pex_root'] = value

  @property
  def internal_cache(self):
    return self.INTERNAL_CACHE

  @property
  def install_cache(self):
    return os.path.join(self.pex_root, 'install')

  @property
  def zip_unsafe_cache(self):
    return os.path.join(self.pex_root, 'code')

  def update(self, other):
    if not isinstance(other, PexInfo):
      raise TypeError('Cannot merge a %r with PexInfo' % type(other))
    self._pex_info.update(other._pex_info)
    self._distributions.update(other.distributions)
    self._requirements.update(other.requirements)

  def dump(self, **kwargs):
    pex_info_copy = self._pex_info.copy()
    pex_info_copy['requirements'] = list(self._requirements)
    pex_info_copy['distributions'] = self._distributions.copy()
    return json.dumps(pex_info_copy, **kwargs)

  def copy(self):
    return self.from_json(self.dump())
