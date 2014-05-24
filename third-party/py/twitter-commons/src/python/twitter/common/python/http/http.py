import contextlib
import hashlib
import os
import socket
import struct
import time

from ..common import safe_delete, safe_mkdir, safe_mkdtemp
from ..compatibility import PY2, PY3
from .tracer import TRACER

if PY3:
  from http.client import parse_headers, HTTPException
  from queue import Queue, Empty
  import urllib.error as urllib_error
  import urllib.parse as urlparse
  import urllib.request as urllib_request
  from urllib.request import addinfourl
else:
  from httplib import HTTPMessage, HTTPException
  from Queue import Queue, Empty
  from urllib import addinfourl
  import urllib2 as urllib_request
  import urllib2 as urllib_error
  import urlparse


class Timeout(Exception):
  pass


class FetchError(Exception):
  """
    Error occurred while fetching via HTTP

    We raise this when we catch urllib or httplib errors because we don't want
    to leak those implementation details to callers.
  """


def deadline(fn, *args, **kw):
  """Helper function to prevent fn(*args, **kw) from running more than
     a specified timeout.

     Takes timeout= kwarg in seconds, which defaults to 150ms (0.150)
  """
  DEFAULT_TIMEOUT_SECS = 0.150

  from threading import Thread
  q = Queue(maxsize=1)
  timeout = kw.pop('timeout', DEFAULT_TIMEOUT_SECS)
  class AnonymousThread(Thread):
    def run(self):
      q.put(fn(*args, **kw))
  AnonymousThread().start()
  try:
    return q.get(timeout=timeout)
  except Empty:
    raise Timeout


class Web(object):
  NS_TIMEOUT_SECS = 5.0
  CONN_TIMEOUT = 1.0
  SCHEME_TO_PORT = {
    'ftp': 21,
    'http': 80,
    'https': 443
  }

  def _resolves(self, fullurl):
    try:
      return socket.gethostbyname(fullurl.hostname)
    except socket.gaierror:
      return ''

  def _reachable(self, fullurl, conn_timeout=None):
    port = fullurl.port if fullurl.port else self.SCHEME_TO_PORT.get(fullurl.scheme, 80)
    try:
      conn = socket.create_connection(
          (fullurl.hostname, port), timeout=(conn_timeout or self.CONN_TIMEOUT))
      conn.close()
      return True
    except (socket.error, socket.timeout):
      TRACER.log('Failed to connect to %s within deadline' % urlparse.urlunparse(fullurl))
      return False

  def reachable(self, url, conn_timeout=None):
    """Do we think this URL is reachable?

       If this isn't here, it takes 5-30s to timeout on DNS resolution for
       certain hosts, so we prefetch DNS at a cost of 5-8ms but cap
       resolution at something sane, e.g. 5s.
    """
    fullurl = urlparse.urlparse(url)
    if not fullurl.scheme or not fullurl.netloc:
      return True
    try:
      with TRACER.timed('Resolving %s' % fullurl.hostname, V=2):
        if not deadline(self._resolves, fullurl, timeout=self.NS_TIMEOUT_SECS):
          TRACER.log('Failed to resolve %s' % url)
          return False
    except Timeout:
      TRACER.log('Timed out resolving %s' % fullurl.hostname)
      return False
    with TRACER.timed('Connecting to %s' % fullurl.hostname, V=2):
      return self._reachable(fullurl, conn_timeout=conn_timeout)

  def maybe_local_url(self, url):
    full_url = urlparse.urlparse(url)
    if full_url.scheme == '':
      return 'file://' + os.path.realpath(url)
    return url

  def open(self, url, conn_timeout=None, **kw):
    """
      Wrapper in front of urlopen that more gracefully handles odd network environments.
    """
    url = self.maybe_local_url(url)
    with TRACER.timed('Fetching %s' % url, V=1):
      if not self.reachable(url, conn_timeout=conn_timeout):
        raise FetchError('Could not reach %s within deadline.' % url)
      try:
        return urllib_request.urlopen(url, **kw)
      except (urllib_error.URLError, HTTPException) as exc:
        raise FetchError(exc)


class CachedWeb(object):
  """
    A basic http cache.

    Can act as a failsoft cache: If an object has expired but the fetch fails,
    will fall back to the cached object if failsoft set to True.
  """
  def __init__(self, cache=None, failsoft=True, clock=time, opener=None):
    self._failsoft = failsoft
    self._cache = cache or safe_mkdtemp()
    safe_mkdir(self._cache)
    self._clock = clock
    self._opener = opener or Web()
    super(CachedWeb, self).__init__()

  def __contains__(self, url):
    age = self.age(url)
    return age is not None and age > 0

  def translate_url(self, url):
    return os.path.join(self._cache, hashlib.md5(url.encode('utf8')).hexdigest())

  def translate_all(self, url):
    return ('%(tgt)s %(tgt)s.tmp %(tgt)s.headers %(tgt)s.headers.tmp' % {
        'tgt': self.translate_url(url)
    }).split()

  def age(self, url):
    """Return the age of an object in seconds, or None if object is not in cache."""
    cached_object = self.translate_url(url)
    if not os.path.exists(cached_object):
      return None
    return self._clock.time() - os.path.getmtime(cached_object)

  def expired(self, url, ttl=None):
    age = self.age(url)
    if age is None:
      return True
    if ttl is None:
      return False
    return age > ttl

  def really_open(self, url, conn_timeout=None):
    try:
      return self._opener.open(url, conn_timeout=conn_timeout)
    except urllib_error.HTTPError as fp:
      # HTTPError is a valid addinfourl -- use this instead of raising
      return fp

  def encode_url(self, url, conn_timeout=None):
    target, target_tmp, headers, headers_tmp = self.translate_all(url)
    with contextlib.closing(self.really_open(url, conn_timeout=conn_timeout)) as http_fp:
      # File urls won't have a response code, they'll either open or raise.
      if http_fp.getcode() and http_fp.getcode() != 200:
        raise urllib_error.URLError('Non-200 response code from %s' % url)
      with TRACER.timed('Caching %s' % url, V=2):
        with open(target_tmp, 'wb') as disk_fp:
          disk_fp.write(http_fp.read())
        with open(headers_tmp, 'wb') as headers_fp:
          headers_fp.write(struct.pack('>h', http_fp.code or 0))
          headers_fp.write(str(http_fp.headers).encode('utf8'))
        os.rename(target_tmp, target)
        os.rename(headers_tmp, headers)

  def decode_url(self, url):
    target, _, headers, _ = self.translate_all(url)
    headers_fp = open(headers, 'rb')
    code, = struct.unpack('>h', headers_fp.read(2))
    def make_headers(fp):
      return HTTPMessage(fp) if PY2 else parse_headers(fp)
    return addinfourl(open(target, 'rb'), make_headers(headers_fp), url, code)

  def clear_url(self, url):
    for path in self.translate_all(url):
      safe_delete(path)

  def cache(self, url, conn_timeout=None):
    """cache the contents of a url."""
    try:
      self.encode_url(url, conn_timeout=conn_timeout)
    except urllib_error.URLError:
      self.clear_url(url)
      raise

  def open(self, url, ttl=None, conn_timeout=None):
    """Return a file-like object with the content of the url."""
    expired = self.expired(url, ttl=ttl)
    with TRACER.timed('Opening %s' % ('(cached)' if not expired else '(uncached)'), V=1):
      if expired:
        try:
          self.cache(url, conn_timeout=conn_timeout)
        except (urllib_error.URLError, HTTPException) as exc:
          if not self._failsoft or url not in self:
            raise FetchError(exc)
      return self.decode_url(url)
