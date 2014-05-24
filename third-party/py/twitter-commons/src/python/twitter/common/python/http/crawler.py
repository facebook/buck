import contextlib
from functools import partial
import os
import re
import threading

from ..compatibility import PY3
from .http import CachedWeb, Web, FetchError
from .tracer import TRACER

if PY3:
  from queue import Empty, Queue
  from urllib.parse import urlparse, urljoin
else:
  from Queue import Empty, Queue
  from urlparse import urlparse, urljoin


class CrawlerBase(object):
  """Base class for iterators over links."""
  def __init__(self, opener=None, threads=1):
    self._opener = opener
    self._threads = threads

  @property
  def opener(self):
    return self._opener

  def crawl(self, urls, follow_links=False):
    links, seen = set(), set()
    queue = Queue()
    converged = threading.Event()

    def execute():
      while not converged.is_set():
        try:
          url = queue.get(timeout=0.1)
        except Empty:
          continue
        if url not in seen:
          seen.add(url)
          hrefs, rel_hrefs = self.execute(url)
          links.update(hrefs)
          if follow_links:
            for href in rel_hrefs:
              if href not in seen:
                queue.put(href)
        queue.task_done()

    for url in urls:
      queue.put(url)
    for _ in range(self._threads):
      worker = threading.Thread(target=execute)
      worker.daemon = True
      worker.start()
    queue.join()
    converged.set()
    return links

  def execute(self, url):
    """Return (links, follow_links)."""
    raise NotImplementedError


class PageParser(object):
  HREF_RE = re.compile(r"""href=(?:"([^"]*)"|\'([^\']*)\'|([^>\s\n]*))""", re.I | re.S)
  REL_RE = re.compile(r"""<[^>]*\srel\s*=\s*['"]?([^'">]+)[^>]*>""", re.I)
  REL_SKIP_EXTENSIONS = frozenset(['.zip', '.tar', '.tar.gz', '.tar.bz2', '.tgz', '.exe'])
  REL_TYPES = frozenset(['homepage', 'download'])

  @classmethod
  def href_match_to_url(cls, match):
    def pick(group):
      return '' if group is None else group
    return pick(match.group(1)) or pick(match.group(2)) or pick(match.group(3))

  @classmethod
  def rel_links(cls, page):
    """return rel= links that should be scraped, skipping obviously data links."""
    for match in cls.REL_RE.finditer(page):
      href, rel = match.group(0), match.group(1)
      if rel not in cls.REL_TYPES:
        continue
      href_match = cls.HREF_RE.search(href)
      if href_match:
        href = cls.href_match_to_url(href_match)
        parsed_href = urlparse(href)
        if any(parsed_href.path.endswith(ext) for ext in cls.REL_SKIP_EXTENSIONS):
          continue
        yield href

  @classmethod
  def links(cls, page):
    """return all links on a page, including potentially rel= links."""
    for match in cls.HREF_RE.finditer(page):
      yield cls.href_match_to_url(match)


class Crawler(CrawlerBase):
  """Crawl a url for links."""
  DEFAULT_ENCODING = 'iso-8859-1'

  def __init__(self, cache=None, cache_ttl=3600, enable_cache=True, conn_timeout=None, **kw):
    opener = CachedWeb(cache=cache) if enable_cache else Web()
    self._open = partial(opener.open, ttl=cache_ttl) if enable_cache else opener.open
    self._conn_timeout = conn_timeout
    super(Crawler, self).__init__(opener=opener, **kw)

  @classmethod
  def decode_page(cls, infofp):
    hdr = infofp.headers
    # 2.x / 3.x shenanigans
    charset = hdr.getparam('charset') if hasattr(hdr, 'getparam') else hdr.get_param('charset')
    buf = infofp.read()
    try:
      return buf.decode(charset or cls.DEFAULT_ENCODING)
    except ValueError:
      # there is no universal base class for decoding errors
      TRACER.log('Failed to decode %s using %s' % (infofp.url, charset))
      return buf.decode(cls.DEFAULT_ENCODING)

  def _local_execute(self, path):
    try:
      dirents = os.listdir(path)
    except OSError as e:
      TRACER.log('Failed to fetch %s: %s' % (path, e))
      return set(), set()
    def partition(L, pred):
      return filter(lambda v: not pred(v), L), filter(lambda v: pred(v), L)
    return partition([os.path.join(path, fn) for fn in dirents], os.path.isdir)

  def _remote_execute(self, url):
    try:
      with contextlib.closing(self._open(url, conn_timeout=self._conn_timeout)) as index_fp:
        index_content = self.decode_page(index_fp)
    except FetchError as e:
      TRACER.log('Failed to fetch %s: %s' % (url, e))
      return set(), set()
    links = set(urljoin(url, link) for link in PageParser.links(index_content))
    rel_links = set(urljoin(url, link) for link in PageParser.rel_links(index_content))
    return links, rel_links

  def execute(self, url):
    with TRACER.timed('Crawling %s' % url):
      parsed_url = urlparse(url)
      if parsed_url.scheme in ('', 'file'):
        return self._local_execute(parsed_url.path)
      elif parsed_url.scheme in ('http', 'https'):
        return self._remote_execute(url)
      else:
        TRACER.log('Unknown scheme %s, skipping.' % parsed_url.scheme)
        return set(), set()
