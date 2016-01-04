# Copyright 2014 Pants project contributors (see CONTRIBUTORS.md).
# Licensed under the Apache License, Version 2.0 (see LICENSE).

# This file contains several 2.x/3.x compatibility checkstyle violations for a reason
# checkstyle: noqa

from abc import ABCMeta
from numbers import Integral, Real
from sys import version_info as sys_version_info

# TODO(wickman)  Since the io package is available in 2.6.x, use that instead of
# cStringIO/StringIO
try:
  # CPython 2.x
  from cStringIO import StringIO
except ImportError:
  try:
    # Python 2.x
    from StringIO import StringIO
  except:
    # Python 3.x
    from io import StringIO
    from io import BytesIO

AbstractClass = ABCMeta('AbstractClass', (object,), {})
PY2 = sys_version_info[0] == 2
PY3 = sys_version_info[0] == 3
StringIO = StringIO
BytesIO = BytesIO if PY3 else StringIO

integer = (Integral,)
real = (Real,)
numeric = integer + real
string = (str,) if PY3 else (str, unicode)
bytes = (bytes,)

if PY2:
  def to_bytes(st, encoding='utf-8'):
    if isinstance(st, unicode):
      return st.encode(encoding)
    elif isinstance(st, bytes):
      return st
    else:
      raise ValueError('Cannot convert %s to bytes' % type(st))
else:
  def to_bytes(st, encoding='utf-8'):
    if isinstance(st, str):
      return st.encode(encoding)
    elif isinstance(st, bytes):
      return st
    else:
      raise ValueError('Cannot convert %s to bytes.' % type(st))

_PY3_EXEC_FUNCTION = """
def exec_function(ast, globals_map):
  locals_map = globals_map
  exec ast in globals_map, locals_map
  return locals_map
"""

if PY3:
  def exec_function(ast, globals_map):
    locals_map = globals_map
    exec(ast, globals_map, locals_map)
    return locals_map
else:
  eval(compile(_PY3_EXEC_FUNCTION, "<exec_function>", "exec"))

if PY3:
  from contextlib import contextmanager, ExitStack

  @contextmanager
  def nested(*context_managers):
    enters = []
    with ExitStack() as stack:
      for manager in context_managers:
        enters.append(stack.enter_context(manager))
      yield tuple(enters)

else:
  from contextlib import nested


__all__ = (
  'AbstractClass',
  'BytesIO',
  'PY2',
  'PY3',
  'StringIO',
  'bytes',
  'exec_function',
  'nested',
  'string',
  'to_bytes',
)
