import ctypes
import platform
import pkg_resources

lib = 'libfoo'
if platform.system() == 'Linux':
    lib += '.so'
elif platform.system() == 'Darwin':
    lib += '.dylib'
elif platform.system() == 'Windows':
    lib += '.dll'
else:
    raise Exception('unknown system: ' + platform.system())

if __name__ == '__main__':
    # Due to a pecularity of Python, we can't use __name__ here, because this is
    # __main__ and Python loses track of where the resources are.
    ctypes.CDLL(pkg_resources.resource_filename('main_with_native_libs', lib)).foo()
