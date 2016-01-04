# Copyright 2014 Pants project contributors (see CONTRIBUTORS.md).
# Licensed under the Apache License, Version 2.0 (see LICENSE).

from __future__ import absolute_import, print_function

import itertools
import os
import site
import sys
import uuid

from pkg_resources import (
    DistributionNotFound,
    Environment,
    Requirement,
    WorkingSet,
    find_distributions
)

from .common import die, open_zip, safe_mkdir, safe_rmtree
from .interpreter import PythonInterpreter
from .package import distribution_compatible
from .pex_builder import PEXBuilder
from .pex_info import PexInfo
from .tracer import TRACER
from .util import CacheHelper, DistributionHelper


class PEXEnvironment(Environment):
  @classmethod
  def force_local(cls, pex, pex_info):
    if pex_info.code_hash is None:
      # Do not support force_local if code_hash is not set. (It should always be set.)
      return pex
    explode_dir = os.path.join(pex_info.zip_unsafe_cache, pex_info.code_hash)
    TRACER.log('PEX is not zip safe, exploding to %s' % explode_dir)
    if not os.path.exists(explode_dir):
      explode_tmp = explode_dir + '.' + uuid.uuid4().hex
      with TRACER.timed('Unzipping %s' % pex):
        try:
          safe_mkdir(explode_tmp)
          with open_zip(pex) as pex_zip:
            pex_files = (x for x in pex_zip.namelist()
                         if not x.startswith(PEXBuilder.BOOTSTRAP_DIR) and
                            not x.startswith(PexInfo.INTERNAL_CACHE))
            pex_zip.extractall(explode_tmp, pex_files)
        except:  # noqa: T803
          safe_rmtree(explode_tmp)
          raise
      TRACER.log('Renaming %s to %s' % (explode_tmp, explode_dir))
      os.rename(explode_tmp, explode_dir)
    return explode_dir

  @classmethod
  def update_module_paths(cls, new_code_path):
    # Force subsequent imports to come from the .pex directory rather than the .pex file.
    TRACER.log('Adding to the head of sys.path: %s' % new_code_path)
    sys.path.insert(0, new_code_path)
    for name, module in sys.modules.items():
      if hasattr(module, "__path__"):
        module_dir = os.path.join(new_code_path, *name.split("."))
        TRACER.log('Adding to the head of %s.__path__: %s' % (module.__name__, module_dir))
        module.__path__.insert(0, module_dir)

  @classmethod
  def write_zipped_internal_cache(cls, pex, pex_info):
    prefix_length = len(pex_info.internal_cache) + 1
    existing_cached_distributions = []
    newly_cached_distributions = []
    zip_safe_distributions = []
    with open_zip(pex) as zf:
      # Distribution names are the first element after ".deps/" and before the next "/"
      distribution_names = set(filter(None, (filename[prefix_length:].split('/')[0]
          for filename in zf.namelist() if filename.startswith(pex_info.internal_cache))))
      # Create Distribution objects from these, and possibly write to disk if necessary.
      for distribution_name in distribution_names:
        internal_dist_path = '/'.join([pex_info.internal_cache, distribution_name])
        # First check if this is already cached
        dist_digest = pex_info.distributions.get(distribution_name) or CacheHelper.zip_hash(
            zf, internal_dist_path)
        cached_location = os.path.join(pex_info.install_cache, '%s.%s' % (
          distribution_name, dist_digest))
        if os.path.exists(cached_location):
          dist = DistributionHelper.distribution_from_path(cached_location)
          existing_cached_distributions.append(dist)
          continue
        else:
          dist = DistributionHelper.distribution_from_path(os.path.join(pex, internal_dist_path))
          if DistributionHelper.zipsafe(dist) and not pex_info.always_write_cache:
            zip_safe_distributions.append(dist)
            continue

        with TRACER.timed('Caching %s' % dist):
          newly_cached_distributions.append(
            CacheHelper.cache_distribution(zf, internal_dist_path, cached_location))
    return existing_cached_distributions, newly_cached_distributions, zip_safe_distributions

  @classmethod
  def load_internal_cache(cls, pex, pex_info):
    """Possibly cache out the internal cache."""
    internal_cache = os.path.join(pex, pex_info.internal_cache)
    with TRACER.timed('Searching dependency cache: %s' % internal_cache, V=2):
      if os.path.isdir(pex):
        for dist in find_distributions(internal_cache):
          yield dist
      else:
        for dist in itertools.chain(*cls.write_zipped_internal_cache(pex, pex_info)):
          yield dist

  def __init__(self, pex, pex_info, interpreter=None, **kw):
    self._internal_cache = os.path.join(pex, pex_info.internal_cache)
    self._pex = pex
    self._pex_info = pex_info
    self._activated = False
    self._working_set = None
    self._interpreter = interpreter or PythonInterpreter.get()
    super(PEXEnvironment, self).__init__(
        search_path=sys.path if pex_info.inherit_path else [], **kw)

  def update_candidate_distributions(self, distribution_iter):
    for dist in distribution_iter:
      if self.can_add(dist):
        with TRACER.timed('Adding %s' % dist, V=2):
          self.add(dist)

  def can_add(self, dist):
    return distribution_compatible(dist, self._interpreter, self.platform)

  def activate(self):
    if not self._activated:
      with TRACER.timed('Activating PEX virtual environment from %s' % self._pex):
        self._working_set = self._activate()
      self._activated = True

    return self._working_set

  def _resolve(self, working_set, reqs):
    reqs = reqs[:]
    unresolved_reqs = set()
    resolveds = set()

    # Resolve them one at a time so that we can figure out which ones we need to elide should
    # there be an interpreter incompatibility.
    for req in reqs:
      with TRACER.timed('Resolving %s' % req, V=2):
        try:
          resolveds.update(working_set.resolve([req], env=self))
        except DistributionNotFound as e:
          TRACER.log('Failed to resolve a requirement: %s' % e)
          unresolved_reqs.add(e.args[0].project_name)
          # Older versions of pkg_resources just call `DistributionNotFound(req)` instead of the
          # modern `DistributionNotFound(req, requirers)` and so we may not have the 2nd requirers
          # slot at all.
          if len(e.args) >= 2 and e.args[1]:
            unresolved_reqs.update(e.args[1])

    unresolved_reqs = set([req.lower() for req in unresolved_reqs])

    if unresolved_reqs:
      TRACER.log('Unresolved requirements:')
      for req in unresolved_reqs:
        TRACER.log('  - %s' % req)
      TRACER.log('Distributions contained within this pex:')
      if not self._pex_info.distributions:
        TRACER.log('  None')
      else:
        for dist in self._pex_info.distributions:
          TRACER.log('  - %s' % dist)
      if not self._pex_info.ignore_errors:
        die('Failed to execute PEX file, missing compatible dependencies for:\n%s' % (
            '\n'.join(map(str, unresolved_reqs))))

    return resolveds

  def _activate(self):
    self.update_candidate_distributions(self.load_internal_cache(self._pex, self._pex_info))

    if not self._pex_info.zip_safe and os.path.isfile(self._pex):
      self.update_module_paths(self.force_local(self._pex, self._pex_info))

    all_reqs = [Requirement.parse(req) for req in self._pex_info.requirements]

    working_set = WorkingSet([])
    resolved = self._resolve(working_set, all_reqs)

    for dist in resolved:
      with TRACER.timed('Activating %s' % dist, V=2):
        working_set.add(dist)

        if os.path.isdir(dist.location):
          with TRACER.timed('Adding sitedir', V=2):
            site.addsitedir(dist.location)

        dist.activate()

    return working_set
