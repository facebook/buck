# Copyright (c) Facebook, Inc. and its affiliates.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

from __future__ import print_function

import contextlib
import errno
import os


BUCK_LOCK_FILE_NAME = ".buck_lock"


if os.name == "posix":
    import fcntl

    def _fcntl_with_exception_handling(fh, exclusive, wait):
        lock_type = fcntl.LOCK_EX if exclusive else fcntl.LOCK_SH
        if not wait:
            lock_type |= fcntl.LOCK_NB
        try:
            fcntl.lockf(fh.fileno(), lock_type)
            return True
        except IOError as e:
            if e.errno in [errno.EACCES, errno.EAGAIN]:
                return False
            raise


else:

    def _fcntl_with_exception_handling(fh, exclusive, wait):
        return True


def acquire_shared_lock(fh, wait=False):
    """ Acquires a shared posix advisory lock on a file in a non-blocking way. The f argument
    should be a file (ideally opened with 'a+', as that works for both shared and exclusive locks).
    Returns True on success, False if the lock cannot be taken, throws on error.
    """
    return _fcntl_with_exception_handling(fh, exclusive=False, wait=wait)


@contextlib.contextmanager
def shared_lock(path, *args, **kwargs):
    with open(path, "a+b") as fh:
        if acquire_shared_lock(fh, *args, **kwargs):
            yield fh
        else:
            raise OSError("Failed to acquire shared lock on %s" % path)


def acquire_exclusive_lock(fh, wait=False):
    """ Acquires an exclusive posix advisory lock on a file in a non-blocking way. The f argument
    should be a file (ideally opened with 'a+', as that works for both shared and exclusive locks).
    Returns True on success, False if the lock cannot be taken, throws on error.
    """
    return _fcntl_with_exception_handling(fh, exclusive=True, wait=wait)


@contextlib.contextmanager
def exclusive_lock(path, *args, **kwargs):
    with open(path, "a+b") as fh:
        if acquire_exclusive_lock(fh, *args, **kwargs):
            yield fh
        else:
            raise OSError("Failed to acquire exclusive lock on %s" % path)


def rmtree_if_can_lock(root):
    """ Removes the directory and sub-directories under root, except any directories which have
    a `.buck_lock` file present, which are only deleted if an exclusive lock can be obtained on
    the lock file.
    """
    lock_file_path = os.path.join(root, BUCK_LOCK_FILE_NAME)
    lock_file = None
    if os.path.exists(lock_file_path):
        lock_file = open(lock_file_path, "a+")  # noqa: P201
        if not acquire_exclusive_lock(lock_file):
            lock_file.close()
            return
    for name in os.listdir(root):
        p = os.path.join(root, name)
        if os.path.isdir(p):
            rmtree_if_can_lock(p)
        else:
            try:
                os.unlink(p)
            except OSError:
                # Ignore errors like shutil.rmtree
                pass
    try:
        os.rmdir(root)
    except OSError:
        # Ignore errors like shutil.rmtree
        pass
    if lock_file is not None:
        lock_file.close()
