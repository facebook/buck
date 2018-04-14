import ctypes
import platform

lib = 'libfoo'
if platform.system() == 'Linux':
    lib += '.so'
elif platform.system() == 'Darwin':
    lib += '.dylib'
elif platform.system() == 'Windows':
    lib += '.dll'
else:
    raise Exception('unknown system: ' + platform.system())

ctypes.CDLL(lib).foo()
