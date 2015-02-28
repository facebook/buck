import tempfile
import subprocess


# Backport of the Python 2.7 subprocess.CalledProcessError, including
# an `output` attribute.
class CalledProcessError(subprocess.CalledProcessError):
    def __init__(self, returncode, cmd, output=None):
        super(CalledProcessError, self).__init__(returncode, cmd)
        self.output = output


# Backport of the Python 2.7 subprocess.check_output. Taken from
# http://hg.python.org/cpython/file/71cb8f605f77/Lib/subprocess.py
# Copyright (c) 2003-2005 by Peter Astrand <astrand@lysator.liu.se>
# Licensed to PSF under a Contributor Agreement.
# See http://www.python.org/2.4/license for licensing details.
def check_output(*popenargs, **kwargs):
    if 'stdout' in kwargs:
        raise ValueError('stdout argument not allowed, it will be overridden.')
    process = subprocess.Popen(stdout=subprocess.PIPE, *popenargs, **kwargs)
    output, unused_err = process.communicate()
    retcode = process.poll()
    if retcode:
        cmd = kwargs.get("args")
        if cmd is None:
            cmd = popenargs[0]
        raise CalledProcessError(retcode, cmd, output=output)
    return output


class EmptyTempFile(object):

    def __init__(self, prefix=None, dir=None, closed=True):
        self.file, self.name = tempfile.mkstemp(prefix=prefix, dir=dir)
        if closed:
            os.close(self.file)
        self.closed = closed

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.close()
        os.remove(self.name)

    def close(self):
        if not self.closed:
            os.close(self.file)
        self.closed = True

    def fileno(self):
        return self.file
