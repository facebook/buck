# Detect Python build configuration, a la python-config.

from __future__ import print_function
import json
import sys
import os
from distutils import sysconfig

out = {}
if hasattr(sys, 'pypy_version_info'):
    out['interpreter_name'] = 'PyPy'
elif sys.platform.startswith('java'):
    out['interpreter_name'] = 'Jython'
else:
    out['interpreter_name'] = 'CPython'
out['version_string'] = '{0}.{1}'.format(sys.version_info[0], sys.version_info[1])

getvar = sysconfig.get_config_var


def get_split_var(name):
    val = getvar(name)
    if val is None:
        return []
    else:
        return val.split()

out['preprocessor_flags'] = [
    '-I' + sysconfig.get_python_inc(),
    '-I' + sysconfig.get_python_inc(plat_specific=True)
]
out['compiler_flags'] = out['preprocessor_flags'][:]
out['compiler_flags'].extend(get_split_var('CFLAGS'))
out['linker_flags'] = ['-lpython' + getvar('VERSION')]
out['linker_flags'].extend(get_split_var('LIBS'))
out['linker_flags'].extend(get_split_var('SYSLIBS'))
if not getvar('Py_ENABLE_SHARED') and getvar('LIBPL'):
    out['linker_flags'].insert(0, '-L' + getvar('LIBPL'))
if not getvar('PYTHONFRAMEWORK'):
    out['linker_flags'].extend(get_split_var('LINKFORSHARED'))
out['extension_suffix'] = getvar('SO') or '.so'

print(json.dumps(out))
