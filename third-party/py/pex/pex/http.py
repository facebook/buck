# Copyright 2015 Pants project contributors (see CONTRIBUTORS.md).
# Licensed under the Apache License, Version 2.0 (see LICENSE).

import contextlib
import hashlib
import os
import shutil
import uuid
from abc import abstractmethod
from email import message_from_string

from .common import safe_mkdtemp, safe_open
from .compatibility import PY3, AbstractClass
from .tracer import TRACER
from .variables import ENV
from .version import __version__ as PEX_VERSION

try:
  import requests
except ImportError:
  requests = None

try:
  from cachecontrol import CacheControl
  from cachecontrol.caches import FileCache
except ImportError:
  CacheControl = FileCache = None

if PY3:
  import urllib.request as urllib_request
else:
  import urllib2 as urllib_request

# This is available as hashlib.algorithms_guaranteed in >=3.2 and as
# hashlib.algorithms in >=2.7, but not available in 2.6, so we enumerate
# here.
HASHLIB_ALGORITHMS = frozenset(['md5', 'sha1', 'sha224', 'sha256', 'sha384', 'sha512'])


class Context(AbstractClass):
  """Encapsulate the networking necessary to do requirement resolution.

  At a minimum, the Context must implement ``open(link)`` by returning a
  file-like object.  Reference implementations of ``read(link)`` and
  ``fetch(link)`` are provided based upon ``open(link)`` but may be further
  specialized by individual implementations.
  """

  DEFAULT_ENCODING = 'iso-8859-1'

  class Error(Exception):
    """Error base class for Contexts to wrap application-specific exceptions."""
    pass

  _REGISTRY = []

  @classmethod
  def register(cls, context_impl):
    """Register a concrete implementation of a :class:`Context` to be recognized."""
    cls._REGISTRY.insert(0, context_impl)

  @classmethod
  def get(cls):
    for context_class in cls._REGISTRY:
      try:
        context = context_class()
        TRACER.log('Constructed %s context %r' % (context.__class__.__name__, context), V=4)
        return context
      except cls.Error:
        continue
    raise cls.Error('Could not initialize a request context.')

  @abstractmethod
  def open(self, link):
    """Return an open file-like object to the link.

    :param link: The :class:`Link` to open.
    """

  def read(self, link):
    """Return the binary content associated with the link.

    :param link: The :class:`Link` to read.
    """
    with contextlib.closing(self.open(link)) as fp:
      return fp.read()

  def content(self, link):
    """Return the encoded content associated with the link.

    :param link: The :class:`Link` to read.
    """

  def fetch(self, link, into=None):
    """Fetch the binary content associated with the link and write to a file.

    :param link: The :class:`Link` to fetch.
    :keyword into: If specified, write into the directory ``into``.  If ``None``, creates a new
      temporary directory that persists for the duration of the interpreter.
    """
    target = os.path.join(into or safe_mkdtemp(), link.filename)

    if os.path.exists(target):
      # Assume that if the local file already exists, it is safe to use.
      return target

    with TRACER.timed('Fetching %s' % link.url, V=2):
      target_tmp = '%s.%s' % (target, uuid.uuid4())
      with contextlib.closing(self.open(link)) as in_fp:
        with safe_open(target_tmp, 'wb') as out_fp:
          shutil.copyfileobj(in_fp, out_fp)

    os.rename(target_tmp, target)
    return target


class UrllibContext(Context):
  """Default Python standard library Context."""

  def open(self, link):
    return urllib_request.urlopen(link.url)

  def content(self, link):
    if link.local:
      raise self.Error('Context.content only works with remote URLs.')

    with contextlib.closing(self.open(link)) as fp:
      encoding = message_from_string(str(fp.headers)).get_content_charset(self.DEFAULT_ENCODING)
      return fp.read().decode(encoding, 'replace')

  def __init__(self, *args, **kw):
    TRACER.log('Warning, using a UrllibContext which is known to be flaky.')
    TRACER.log('Please build pex with the requests module for more reliable downloads.')
    super(UrllibContext, self).__init__(*args, **kw)


Context.register(UrllibContext)


class StreamFilelike(object):
  """A file-like object wrapper around requests streams that performs hash validation."""

  @classmethod
  def detect_algorithm(cls, link):
    """Detect the hashing algorithm from the fragment in the link, if any."""
    if any(link.fragment.startswith('%s=' % algorithm) for algorithm in HASHLIB_ALGORITHMS):
      algorithm, value = link.fragment.split('=', 2)
      try:
        return hashlib.new(algorithm), value
      except ValueError:  # unsupported algorithm
        return None, None
    return None, None

  def __init__(self, request, link, chunk_size=16384):
    self._iterator = request.iter_content(chunk_size)
    self.encoding = request.encoding
    self._bytes = b''
    self._link = link
    self._hasher, self._hash_value = self.detect_algorithm(link)

  def read(self, length=None):
    while length is None or len(self._bytes) < length:
      try:
        next_chunk = next(self._iterator)
        if self._hasher:
          self._hasher.update(next_chunk)
        self._bytes += next_chunk
      except StopIteration:
        self._validate()
        break
    if length is None:
      length = len(self._bytes)
    chunk, self._bytes = self._bytes[:length], self._bytes[length:]
    return chunk

  def _validate(self):
    if self._hasher:
      if self._hash_value != self._hasher.hexdigest():
        raise Context.Error('%s failed checksum!' % (self._link.url))
      else:
        TRACER.log('Validated %s (%s)' % (self._link.filename, self._link.fragment), V=3)

  def close(self):
    pass


class RequestsContext(Context):
  """A requests-based Context."""
  USER_AGENT = 'pex/%s' % PEX_VERSION

  @staticmethod
  def _create_session(max_retries):
    session = requests.session()
    retrying_adapter = requests.adapters.HTTPAdapter(max_retries=max_retries)
    session.mount('http://', retrying_adapter)
    session.mount('https://', retrying_adapter)
    return session

  def __init__(self, session=None, verify=True, env=ENV):
    if requests is None:
      raise RuntimeError('requests is not available.  Cannot use a RequestsContext.')

    self._verify = verify

    max_retries = env.PEX_HTTP_RETRIES
    if max_retries < 0:
      raise ValueError('max_retries may not be negative.')

    self._max_retries = max_retries
    self._session = session or self._create_session(self._max_retries)

  def open(self, link):
    # requests does not support file:// -- so we must short-circuit manually
    if link.local:
      return open(link.path, 'rb')  # noqa: T802
    for attempt in range(self._max_retries + 1):
      try:
        return StreamFilelike(self._session.get(
            link.url, verify=self._verify, stream=True, headers={'User-Agent': self.USER_AGENT}),
            link)
      except requests.exceptions.ReadTimeout:
        # Connect timeouts are handled by the HTTPAdapter, unfortunately read timeouts are not
        # so we'll retry them ourselves.
        TRACER.log('Read timeout trying to fetch %s, retrying. %d retries remain.' % (
            link.url,
            self._max_retries - attempt))
      except requests.exceptions.RequestException as e:
        raise self.Error(e)

    raise self.Error(
        requests.packages.urllib3.exceptions.MaxRetryError(
            None,
            link,
            'Exceeded max retries of %d' % self._max_retries))

  def content(self, link):
    if link.local:
      raise self.Error('Context.content only works with remote URLs.')

    with contextlib.closing(self.open(link)) as request:
      return request.read().decode(request.encoding or self.DEFAULT_ENCODING, 'replace')


if requests:
  Context.register(RequestsContext)


class CachedRequestsContext(RequestsContext):
  """A requests-based Context with CacheControl support."""

  DEFAULT_CACHE = '~/.pex/cache'

  def __init__(self, cache=None, **kw):
    self._cache = os.path.realpath(os.path.expanduser(cache or self.DEFAULT_CACHE))
    super(CachedRequestsContext, self).__init__(
        CacheControl(requests.session(), cache=FileCache(self._cache)), **kw)


if CacheControl:
  Context.register(CachedRequestsContext)
