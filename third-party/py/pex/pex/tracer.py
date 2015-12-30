# Copyright 2014 Pants project contributors (see CONTRIBUTORS.md).
# Licensed under the Apache License, Version 2.0 (see LICENSE).

import sys
import threading
import time
from contextlib import contextmanager

from .variables import ENV

__all__ = ('TraceLogger',)


class Trace(object):
  __slots__ = ('msg', 'verbosity', 'parent', 'children', '_clock', '_start', '_stop')

  def __init__(self, msg, parent=None, verbosity=1, clock=time):
    self.msg = msg
    self.verbosity = verbosity
    self.parent = parent
    if parent is not None:
      parent.children.append(self)
    self.children = []
    self._clock = clock
    self._start = self._clock.time()
    self._stop = None

  def stop(self):
    self._stop = self._clock.time()

  def duration(self):
    assert self._stop is not None
    return self._stop - self._start


class TraceLogger(object):
  """
    A multi-threaded tracer.
  """

  def __init__(self, predicate=None, output=sys.stderr, clock=time, prefix=''):
    """
      If predicate specified, it should take a "verbosity" integer and determine whether
      or not to log, e.g.

        def predicate(verbosity):
          try:
            return verbosity < int(os.environ.get('APP_VERBOSITY', 0))
          except ValueError:
            return False

      output defaults to sys.stderr, but can take any file-like object.
    """
    self._predicate = predicate or (lambda verbosity: True)
    self._length = None
    self._output = output
    self._isatty = getattr(output, 'isatty', False) and output.isatty()
    self._lock = threading.RLock()
    self._local = threading.local()
    self._clock = clock
    self._prefix = prefix

  def should_log(self, V):
    return self._predicate(V)

  def log(self, msg, V=1, end='\n'):
    if not self.should_log(V):
      return
    if not self._isatty and end == '\r':
      # force newlines if we're not a tty
      end = '\n'
    trailing_whitespace = ''
    with self._lock:
      if self._length and self._length > (len(self._prefix) + len(msg)):
        trailing_whitespace = ' ' * (self._length - len(msg) - len(self._prefix))
      self._output.write(''.join([self._prefix, msg, trailing_whitespace, end]))
      self._output.flush()
      self._length = (len(self._prefix) + len(msg)) if end == '\r' else 0

  def print_trace_snippet(self):
    parent = self._local.parent
    parent_verbosity = parent.verbosity
    if not self.should_log(parent_verbosity):
      return
    traces = []
    while parent:
      if self.should_log(parent.verbosity):
        traces.append(parent.msg)
      parent = parent.parent
    self.log(' :: '.join(reversed(traces)), V=parent_verbosity, end='\r')

  def print_trace(self, indent=0, node=None):
    node = node or self._local.parent
    with self._lock:
      self.log(' ' * indent + ('%s: %.1fms' % (node.msg, 1000.0 * node.duration())),
               V=node.verbosity)
      for child in node.children:
        self.print_trace(indent=indent + 2, node=child)

  @contextmanager
  def timed(self, msg, V=1):
    if getattr(self._local, 'parent', None) is None:
      self._local.parent = Trace(msg, verbosity=V, clock=self._clock)
    else:
      parent = self._local.parent
      self._local.parent = Trace(msg, parent=parent, verbosity=V, clock=self._clock)
    self.print_trace_snippet()
    yield
    self._local.parent.stop()
    if self._local.parent.parent is not None:
      self._local.parent = self._local.parent.parent
    else:
      self.print_trace()
      self._local.parent = None


TRACER = TraceLogger(
   predicate=lambda verbosity: verbosity <= ENV.PEX_VERBOSE,
   prefix='pex: '
)
