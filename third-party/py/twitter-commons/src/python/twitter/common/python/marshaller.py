try:
  from imp import get_magic
  HAS_MAGIC = True
except ImportError:
  HAS_MAGIC = False

import marshal
import struct
import time

from .compatibility import BytesIO, bytes as compatibility_bytes


class CodeTimestamp(object):
  TIMESTAMP_RANGE = (4, 8)

  @classmethod
  def from_timestamp(timestamp):
    return CodeTimestamp(timestamp)

  @classmethod
  def from_object(pyc_object):
    stamp = time.localtime(
        struct.unpack('I', pyc_object[slice(*CodeTimestamp.TIMESTAMP_RANGE)])[0])
    return CodeTimestamp(stamp)

  def __init__(self, stamp=time.time()):
    self._stamp = stamp

  def to_object(self):
    return struct.pack('I', self._stamp)


class CodeMarshaller(object):
  class InvalidCode(Exception): pass

  if HAS_MAGIC:
    MAGIC = struct.unpack('I', get_magic())[0]
  MAGIC_RANGE = (0, 4)
  TIMESTAMP_RANGE = (4, 8)

  @staticmethod
  def from_pyc(pyc):
    if not HAS_MAGIC:
      raise CodeMarshaller.InvalidCode('Interpreter cannot unmarshal .pyc!')
    if not isinstance(pyc, compatibility_bytes) and not hasattr(pyc, 'read'):
      raise CodeMarshaller.InvalidCode(
          "CodeMarshaller.from_pyc expects a code or file-like object!")
    if not isinstance(pyc, compatibility_bytes):
      pyc = pyc.read()
    pyc_magic = struct.unpack('I', pyc[slice(*CodeMarshaller.MAGIC_RANGE)])[0]
    if pyc_magic != CodeMarshaller.MAGIC:
      raise CodeMarshaller.InvalidCode("Bad magic number!  Got 0x%X" % pyc_magic)
    stamp = time.localtime(struct.unpack('I', pyc[slice(*CodeMarshaller.TIMESTAMP_RANGE)])[0])
    try:
      code = marshal.loads(pyc[8:])
    except ValueError as e:
      raise CodeMarshaller.InvalidCode("Unmarshaling error! %s" % e)
    return CodeMarshaller(code, stamp)

  @staticmethod
  def from_py(py, filename):
    stamp = int(time.time())
    code = compile(py.replace('\r\n', '\n').replace('\r', '\n'), filename, 'exec')
    return CodeMarshaller(code, stamp)

  def __init__(self, code, stamp):
    self._code = code
    self._stamp = stamp

  @property
  def code(self):
    return self._code

  def to_pyc(self):
    sio = BytesIO()
    sio.write(struct.pack('I', CodeMarshaller.MAGIC))
    sio.write(struct.pack('I', self._stamp))
    sio.write(marshal.dumps(self._code))
    return sio.getvalue()
