# Copyright 2014 Pants project contributors (see CONTRIBUTORS.md).
# Licensed under the Apache License, Version 2.0 (see LICENSE).

from __future__ import print_function

import os
import shutil
import time
from collections import namedtuple

from pkg_resources import safe_name

from .common import safe_mkdir
from .fetcher import Fetcher
from .interpreter import PythonInterpreter
from .iterator import Iterator, IteratorInterface
from .orderedset import OrderedSet
from .package import Package, distribution_compatible
from .platforms import Platform
from .resolvable import ResolvableRequirement, resolvables_from_iterable
from .resolver_options import ResolverOptionsBuilder
from .tracer import TRACER
from .util import DistributionHelper


class Untranslateable(Exception):
  pass


class Unsatisfiable(Exception):
  pass


class StaticIterator(IteratorInterface):
  """An iterator that iterates over a static list of packages."""

  def __init__(self, packages):
    self._packages = packages

  def iter(self, req):
    for package in self._packages:
      if package.satisfies(req):
        yield package


class _ResolvedPackages(namedtuple('_ResolvedPackages', 'resolvable packages parent')):
  @classmethod
  def empty(cls):
    return cls(None, OrderedSet(), None)

  def merge(self, other):
    if other.resolvable is None:
      return _ResolvedPackages(self.resolvable, self.packages, self.parent)
    return _ResolvedPackages(
        self.resolvable,
        self.packages & other.packages,
        self.parent)


class _ResolvableSet(object):
  @classmethod
  def normalize(cls, name):
    return safe_name(name).lower()

  def __init__(self, tuples=None):
    # A list of _ResolvedPackages
    self.__tuples = tuples or []

  def _collapse(self):
    # Collapse all resolvables by name along with the intersection of all compatible packages.
    # If the set of compatible packages is the empty set, then we cannot satisfy all the
    # specifications for a particular name (e.g. "setuptools==2.2 setuptools>4".)
    #
    # We need to return the resolvable since it carries its own network context and configuration
    # regarding package precedence.  This is arbitrary -- we could just as easily say "last
    # resolvable wins" but it seems highly unlikely this will materially affect anybody
    # adversely but could be the source of subtle resolution quirks.
    resolvables = {}
    for resolved_packages in self.__tuples:
      key = self.normalize(resolved_packages.resolvable.name)
      previous = resolvables.get(key, _ResolvedPackages.empty())
      if previous.resolvable is None:
        resolvables[key] = resolved_packages
      else:
        resolvables[key] = previous.merge(resolved_packages)
    return resolvables

  def _synthesize_parents(self, name):
    def render_resolvable(resolved_packages):
      return '%s%s' % (
          str(resolved_packages.resolvable),
          '(from: %s)' % resolved_packages.parent if resolved_packages.parent else '')
    return ', '.join(
        render_resolvable(resolved_packages) for resolved_packages in self.__tuples
        if self.normalize(resolved_packages.resolvable.name) == self.normalize(name))

  def _check(self):
    # Check whether or not the resolvables in this set are satisfiable, raise an exception if not.
    for name, resolved_packages in self._collapse().items():
      if not resolved_packages.packages:
        raise Unsatisfiable('Could not satisfy all requirements for %s:\n    %s' % (
            resolved_packages.resolvable, self._synthesize_parents(name)))

  def merge(self, resolvable, packages, parent=None):
    """Add a resolvable and its resolved packages."""
    self.__tuples.append(_ResolvedPackages(resolvable, OrderedSet(packages), parent))
    self._check()

  def get(self, name):
    """Get the set of compatible packages given a resolvable name."""
    resolvable, packages, parent = self._collapse().get(
        self.normalize(name), _ResolvedPackages.empty())
    return packages

  def packages(self):
    """Return a snapshot of resolvable => compatible packages set from the resolvable set."""
    return list(self._collapse().values())

  def extras(self, name):
    return set.union(
        *[set(tup.resolvable.extras()) for tup in self.__tuples
          if self.normalize(tup.resolvable.name) == self.normalize(name)])

  def replace_built(self, built_packages):
    """Return a copy of this resolvable set but with built packages.

    :param dict built_packages: A mapping from a resolved package to its locally built package.
    :returns: A new resolvable set with built package replacements made.
    """
    def map_packages(resolved_packages):
      packages = OrderedSet(built_packages.get(p, p) for p in resolved_packages.packages)
      return _ResolvedPackages(resolved_packages.resolvable, packages, resolved_packages.parent)

    return _ResolvableSet([map_packages(rp) for rp in self.__tuples])


class Resolver(object):
  """Interface for resolving resolvable entities into python packages."""

  class Error(Exception): pass

  @classmethod
  def filter_packages_by_interpreter(cls, packages, interpreter, platform):
    return [package for package in packages
        if package.compatible(interpreter.identity, platform)]

  def __init__(self, interpreter=None, platform=None):
    self._interpreter = interpreter or PythonInterpreter.get()
    self._platform = platform or Platform.current()

  def package_iterator(self, resolvable, existing=None):
    if existing:
      existing = resolvable.compatible(StaticIterator(existing))
    else:
      existing = resolvable.packages()
    return self.filter_packages_by_interpreter(existing, self._interpreter, self._platform)

  def build(self, package, options):
    context = options.get_context()
    translator = options.get_translator(self._interpreter, self._platform)
    with TRACER.timed('Fetching %s' % package.url, V=2):
      local_package = Package.from_href(context.fetch(package))
    if local_package is None:
      raise Untranslateable('Could not fetch package %s' % package)
    with TRACER.timed('Translating %s into distribution' % local_package.path, V=2):
      dist = translator.translate(local_package)
    if dist is None:
      raise Untranslateable('Package %s is not translateable by %s' % (package, translator))
    if not distribution_compatible(dist, self._interpreter, self._platform):
      raise Untranslateable(
        'Could not get distribution for %s on platform %s.' % (package, self._platform))
    return dist

  def resolve(self, resolvables, resolvable_set=None):
    resolvables = [(resolvable, None) for resolvable in resolvables]
    resolvable_set = resolvable_set or _ResolvableSet()
    processed_resolvables = set()
    processed_packages = {}
    distributions = {}

    while resolvables:
      while resolvables:
        resolvable, parent = resolvables.pop(0)
        if resolvable in processed_resolvables:
          continue
        packages = self.package_iterator(resolvable, existing=resolvable_set.get(resolvable.name))
        resolvable_set.merge(resolvable, packages, parent)
        processed_resolvables.add(resolvable)

      built_packages = {}
      for resolvable, packages, parent in resolvable_set.packages():
        assert len(packages) > 0, 'ResolvableSet.packages(%s) should not be empty' % resolvable
        package = next(iter(packages))
        if resolvable.name in processed_packages:
          # TODO implement backtracking?
          if package != processed_packages[resolvable.name]:
            raise self.Error('Ambiguous resolvable: %s' % resolvable)
          continue
        if package not in distributions:
          dist = self.build(package, resolvable.options)
          built_package = Package.from_href(dist.location)
          built_packages[package] = built_package
          distributions[built_package] = dist
          package = built_package

        distribution = distributions[package]
        processed_packages[resolvable.name] = package
        new_parent = '%s->%s' % (parent, resolvable) if parent else str(resolvable)
        resolvables.extend(
            (ResolvableRequirement(req, resolvable.options), new_parent) for req in
            distribution.requires(extras=resolvable_set.extras(resolvable.name)))
      resolvable_set = resolvable_set.replace_built(built_packages)

    return list(distributions.values())


class CachingResolver(Resolver):
  """A package resolver implementing a package cache."""

  @classmethod
  def filter_packages_by_ttl(cls, packages, ttl, now=None):
    now = now if now is not None else time.time()
    return [package for package in packages
        if package.remote or package.local and (now - os.path.getmtime(package.path)) < ttl]

  def __init__(self, cache, cache_ttl, *args, **kw):
    self.__cache = cache
    self.__cache_ttl = cache_ttl
    safe_mkdir(self.__cache)
    super(CachingResolver, self).__init__(*args, **kw)

  # Short-circuiting package iterator.
  def package_iterator(self, resolvable, existing=None):
    iterator = Iterator(fetchers=[Fetcher([self.__cache])])
    packages = self.filter_packages_by_interpreter(
        resolvable.compatible(iterator), self._interpreter, self._platform)

    if packages:
      if resolvable.exact:
        return packages

      if self.__cache_ttl:
        packages = self.filter_packages_by_ttl(packages, self.__cache_ttl)
        if packages:
          return packages

    return super(CachingResolver, self).package_iterator(resolvable, existing=existing)

  # Caching sandwich.
  def build(self, package, options):
    # cache package locally
    if package.remote:
      package = Package.from_href(options.get_context().fetch(package, into=self.__cache))
      os.utime(package.path, None)

    # build into distribution
    dist = super(CachingResolver, self).build(package, options)

    # if distribution is not in cache, copy
    target = os.path.join(self.__cache, os.path.basename(dist.location))
    if not os.path.exists(target):
      shutil.copyfile(dist.location, target + '~')
      os.rename(target + '~', target)
    os.utime(target, None)

    return DistributionHelper.distribution_from_path(target)


def resolve(
    requirements,
    fetchers=None,
    interpreter=None,
    platform=None,
    context=None,
    precedence=None,
    cache=None,
    cache_ttl=None):

  """Produce all distributions needed to (recursively) meet `requirements`

  :param requirements: An iterator of Requirement-like things, either
    :class:`pkg_resources.Requirement` objects or requirement strings.
  :keyword fetchers: (optional) A list of :class:`Fetcher` objects for locating packages.  If
    unspecified, the default is to look for packages on PyPI.
  :keyword interpreter: (optional) A :class:`PythonInterpreter` object to use for building
    distributions and for testing distribution compatibility.
  :keyword platform: (optional) A PEP425-compatible platform string to use for filtering
    compatible distributions.  If unspecified, the current platform is used, as determined by
    `Platform.current()`.
  :keyword context: (optional) A :class:`Context` object to use for network access.  If
    unspecified, the resolver will attempt to use the best available network context.
  :type threads: int
  :keyword precedence: (optional) An ordered list of allowable :class:`Package` classes
    to be used for producing distributions.  For example, if precedence is supplied as
    ``(WheelPackage, SourcePackage)``, wheels will be preferred over building from source, and
    eggs will not be used at all.  If ``(WheelPackage, EggPackage)`` is suppplied, both wheels and
    eggs will be used, but the resolver will not resort to building anything from source.
  :keyword cache: (optional) A directory to use to cache distributions locally.
  :keyword cache_ttl: (optional integer in seconds) If specified, consider non-exact matches when
    resolving requirements.  For example, if ``setuptools==2.2`` is specified and setuptools 2.2 is
    available in the cache, it will always be used.  However, if a non-exact requirement such as
    ``setuptools>=2,<3`` is specified and there exists a setuptools distribution newer than
    cache_ttl seconds that satisfies the requirement, then it will be used.  If the distribution
    is older than cache_ttl seconds, it will be ignored.  If ``cache_ttl`` is not specified,
    resolving inexact requirements will always result in making network calls through the
    ``context``.
  :returns: List of :class:`pkg_resources.Distribution` instances meeting ``requirements``.
  :raises Unsatisfiable: If ``requirements`` is not transitively satisfiable.
  :raises Untranslateable: If no compatible distributions could be acquired for
    a particular requirement.

  This method improves upon the setuptools dependency resolution algorithm by maintaining sets of
  all compatible distributions encountered for each requirement rather than the single best
  distribution encountered for each requirement.  This prevents situations where ``tornado`` and
  ``tornado==2.0`` could be treated as incompatible with each other because the "best
  distribution" when encountering ``tornado`` was tornado 3.0.  Instead, ``resolve`` maintains the
  set of compatible distributions for each requirement as it is encountered, and iteratively filters
  the set.  If the set of distributions ever becomes empty, then ``Unsatisfiable`` is raised.

  .. versionchanged:: 0.8
    A number of keywords were added to make requirement resolution slightly easier to configure.
    The optional ``obtainer`` keyword was replaced by ``fetchers``, ``translator``, ``context``,
    ``threads``, ``precedence``, ``cache`` and ``cache_ttl``, also all optional keywords.

  .. versionchanged:: 1.0
    The ``translator`` and ``threads`` keywords have been removed.  The choice of threading
    policy is now implicit.  The choice of translation policy is dictated by ``precedence``
    directly.

  .. versionchanged:: 1.0
    ``resolver`` is now just a wrapper around the :class:`Resolver` and :class:`CachingResolver`
    classes.
  """

  builder = ResolverOptionsBuilder(
      fetchers=fetchers,
      precedence=precedence,
      context=context,
  )

  if cache:
    resolver = CachingResolver(cache, cache_ttl, interpreter=interpreter, platform=platform)
  else:
    resolver = Resolver(interpreter=interpreter, platform=platform)

  return resolver.resolve(resolvables_from_iterable(requirements, builder))
