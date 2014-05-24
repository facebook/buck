import itertools
import os
import shutil
import time
import uuid

from .base import requirement_is_exact
from .common import safe_mkdtemp
from .fetcher import PyPIFetcher, Fetcher
from .http import Crawler
from .package import (
     EggPackage,
     Package,
     SourcePackage,
     WheelPackage,
)
from .platforms import Platform
from .tracer import TRACER
from .translator import ChainedTranslator, Translator


class Obtainer(object):
  """
    A requirement obtainer.

    An Obtainer takes a Crawler, a list of Fetchers (which take requirements
    and tells us where to look for them) and a list of Translators (which
    translate egg or source packages into usable distributions) and turns them
    into a cohesive requirement pipeline.

    >>> from twitter.common.python.http import Crawler
    >>> from twitter.common.python.obtainer import Obtainer
    >>> from twitter.common.python.fetcher import PyPIFetcher
    >>> from twitter.common.python.resolver import Resolver
    >>> from twitter.common.python.translator import Translator
    >>> obtainer = Obtainer(Crawler(), [PyPIFetcher()], [Translator.default()])
    >>> resolver = Resolver(obtainer)
    >>> distributions = resolver.resolve(['ansicolors', 'elementtree', 'mako', 'markdown', 'psutil',
    ...                                   'pygments', 'pylint', 'pytest'])
    >>> for d in distributions: d.activate()
  """
  DEFAULT_PACKAGE_PRECEDENCE = (
      WheelPackage,
      EggPackage,
      SourcePackage,
  )

  @classmethod
  def default(cls, platform=Platform.current(), interpreter=None):
    translator = Translator.default(platform=platform, interpreter=interpreter)
    return cls(translators=translator)

  @classmethod
  def package_type_precedence(cls, package, precedence=DEFAULT_PACKAGE_PRECEDENCE):
    for rank, package_type in enumerate(reversed(precedence)):
      if isinstance(package, package_type):
        return rank
    # If we do not recognize the package, it gets lowest precedence
    return -1

  @classmethod
  def package_precedence(cls, package, precedence=DEFAULT_PACKAGE_PRECEDENCE):
    return (package.version, cls.package_type_precedence(package, precedence=precedence))

  def __init__(self, crawler=None,
                     fetchers=None,
                     translators=None,
                     precedence=DEFAULT_PACKAGE_PRECEDENCE):
    self._crawler = crawler or Crawler()
    self._fetchers = fetchers or [PyPIFetcher()]
    if isinstance(translators, (list, tuple)):
      self._translator = ChainedTranslator(*translators)
    else:
      self._translator = translators or Translator.default()
    self._precedence = precedence

  def _translate_href(self, href):
    package = Package.from_href(href, opener=self._crawler.opener)
    # Restrict this to a package found in the package precedence list, so that users of
    # obtainers can restrict which distribution formats they support.
    if any(isinstance(package, package_type) for package_type in self._precedence):
      return package

  def _iter_unordered(self, req):
    url_iterator = itertools.chain.from_iterable(fetcher.urls(req) for fetcher in self._fetchers)
    for package in filter(None, map(self._translate_href, self._crawler.crawl(url_iterator))):
      if package.satisfies(req):
        yield package

  def _sort(self, package_list):
    key = lambda package: self.package_precedence(package, self._precedence)
    return sorted(package_list, key=key, reverse=True)

  def _translate_from(self, obtain_set):
    for package in obtain_set:
      dist = self._translator.translate(package)
      if dist:
        return dist

  def iter(self, req):
    """Return a list of packages that satisfy the requirement in best match order."""
    for package in self._sort(self._iter_unordered(req)):
      yield package

  def obtain(self, req_or_package):
    """Given a requirement or package, return a distribution satisfying that requirement."""
    if isinstance(req_or_package, Package):
      return self._translate_from([req_or_package])
    with TRACER.timed('Obtaining %s' % req_or_package):
      return self._translate_from(self.iter(req_or_package))


class CachingObtainer(Obtainer):
  def __init__(self, *args, **kw):
    self.__ttl = kw.pop('ttl', 3600)
    self.__install_cache = kw.pop('install_cache', None) or safe_mkdtemp()
    super(CachingObtainer, self).__init__(*args, **kw)
    self.__cache_obtainer = Obtainer(
        crawler=self._crawler,
        fetchers=[Fetcher([self.__install_cache])],
        translators=self._translator,
        precedence=self._precedence,
    )

  @property
  def ttl(self):
    return self.__ttl

  @property
  def install_cache(self):
    return self.__install_cache

  def _has_expired_ttl(self, dist):
    now = time.time()
    return now - os.path.getmtime(dist.location) >= self.__ttl

  def _dist_can_be_used(self, dist, requirement):
    return requirement_is_exact(requirement) or not self._has_expired_ttl(dist)

  def _set_cached_dist(self, dist):
    target_location = os.path.join(self.__install_cache, os.path.basename(dist.location))
    if os.path.exists(target_location):
      return
    target_tmp = target_location + uuid.uuid4().get_hex()
    shutil.copyfile(dist.location, target_tmp)
    os.rename(target_tmp, target_location)

  def iter(self, req):
    cached_dist = self._translate_from(self.__cache_obtainer.iter(req))
    if cached_dist and self._dist_can_be_used(cached_dist, req):
      for package in self.__cache_obtainer.iter(req):
        yield package
      return
    for package in super(CachingObtainer, self).iter(req):
      yield package

  def obtain(self, req_or_package):
    dist = super(CachingObtainer, self).obtain(req_or_package)
    if dist:
      self._set_cached_dist(dist)
    return dist
