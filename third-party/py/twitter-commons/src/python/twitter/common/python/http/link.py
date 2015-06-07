from __future__ import absolute_import

import contextlib
import os
import posixpath

from ..common import safe_mkdir, safe_mkdtemp
from ..compatibility import PY3
from .http import FetchError

if PY3:
  import urllib.parse as urlparse
else:
  import urlparse


class Link(object):
  """An HTTP link."""

  class Error(Exception): pass
  class InvalidLink(Error): pass
  class UnreadableLink(Error): pass

  @classmethod
  def _normalize(cls, filename):
    return 'file://' + os.path.realpath(os.path.expanduser(filename))

  def __init__(self, url, opener=None):
    self._url = urlparse.urlparse(url)
    if self._url.scheme == '':
      self._url = urlparse.urlparse(self._normalize(url), allow_fragments=False)

  def __eq__(self, link):
    return self.__class__ == link.__class__ and self._url == link._url

  def __hash__(self):
    return hash(self.url)

  @property
  def filename(self):
    return posixpath.basename(self._url.path)

  @property
  def url(self):
    return urlparse.urlunparse(self._url)

  @property
  def local(self):
    """Is the url a local file?"""
    return self._url.scheme in ('', 'file')

  @property
  def remote(self):
    """Is the url a remote file?"""
    return self._url.scheme in ('http', 'https')

  def __repr__(self):
    return '%s(%r)' % (self.__class__.__name__, self.url)

  def fh(self, conn_timeout=None):
    if not self._opener:
      raise self.UnreadableLink("Link cannot be read: no opener supplied.")
    return self._opener.open(self.url, conn_timeout=conn_timeout)

  def fetch(self, location=None, conn_timeout=None):
    """Fetches the link returning the local file path.

    :raises UnreadableLink: if the link could not be fetched.
    """
    if self.local and (location is None or os.path.dirname(self._url.path) == location):
      return self._url.path
    location = location or safe_mkdtemp()
    target = os.path.join(location, self.filename)
    if os.path.exists(target):
      return target
    try:
      with contextlib.closing(self.fh(conn_timeout=conn_timeout)) as url_fp:
        safe_mkdir(os.path.dirname(target))
        with open(target, 'wb') as fp:
          fp.write(url_fp.read())
    except (FetchError, IOError) as e:
      raise self.UnreadableLink('Failed to fetch %s to %s: %s' % (self.url, location, e))
    return target
