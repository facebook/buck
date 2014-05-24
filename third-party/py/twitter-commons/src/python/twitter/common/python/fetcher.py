from __future__ import absolute_import

from abc import abstractmethod
import random

from .base import maybe_requirement
from .compatibility import AbstractClass


class FetcherBase(AbstractClass):
  """
    A fetcher takes a Requirement and tells us where to crawl to find it.
  """
  @abstractmethod
  def urls(self, req):
    raise NotImplementedError


class Fetcher(FetcherBase):
  def __init__(self, urls):
    self._urls = urls

  def urls(self, _):
    return self._urls


class PyPIFetcher(FetcherBase):
  PYPI_BASE = 'pypi.python.org'

  @classmethod
  def resolve_mirrors(cls, base):
    """Resolve mirrors per PEP-0381."""
    import socket
    def crange(ch1, ch2):
      return [chr(ch) for ch in range(ord(ch1), ord(ch2) + 1)]
    last, _, _ = socket.gethostbyname_ex('last.' + base)
    assert last.endswith(cls.PYPI_BASE)
    last_prefix = last.split('.')[0]
    # TODO(user) Is implementing > z really necessary?
    last_prefix = 'z' if len(last_prefix) > 1 else last_prefix[0]
    return ['%c.%s' % (letter, base) for letter in crange('a', last_prefix)]

  def __init__(self, pypi_base=PYPI_BASE, use_mirrors=False):
    self.mirrors = self.resolve_mirrors(pypi_base) if use_mirrors else [pypi_base]

  def urls(self, req):
    req = maybe_requirement(req)
    random_mirror = random.choice(self.mirrors)
    return ['https://%s/simple/%s/' % (random_mirror, req.project_name)]
