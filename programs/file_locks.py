# Copyright 2016-present Facebook, Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may
# not use this file except in compliance with the License. You may obtain
# a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations
# under the License.

import errno
import os

BUCK_LOCK_FILE_NAME = ".buck_lock"


if os.name == "posix":
    import fcntl

    def _fcntl_with_exception_handling(f, exclusive):
        lock_type = fcntl.LOCK_EX if exclusive else fcntl.LOCK_SH
        try:
            fcntl.lockf(f.fileno(), lock_type | fcntl.LOCK_NB)
            return True
        except IOError as e:
            if e.errno in [errno.EACCES, errno.EAGAIN]:
                return False
            raise


else:

    def _fcntl_with_exception_handling(f, exclusive):
        return True


def acquire_shared_lock(f):
    """ Acquires a shared posix advisory lock on a file in a non-blocking way. The f argument
    should be a file (ideally opened with 'a+', as that works for both shared and exclusive locks).
    Returns True on success, False if the lock cannot be taken, throws on error.
    """
    return _fcntl_with_exception_handling(f, exclusive=False)


def acquire_exclusive_lock(f):
    """ Acquires an exclusive posix advisory lock on a file in a non-blocking way. The f argument
    should be a file (ideally opened with 'a+', as that works for both shared and exclusive locks).
    Returns True on success, False if the lock cannot be taken, throws on error.
    """
    return _fcntl_with_exception_handling(f, exclusive=True)


def rmtree_if_can_lock(root):
    """ Removes the directory and sub-directories under root, except any directories which have
    a `.buck_lock` file present, which are only deleted if an exclusive lock can be obtained on
    the lock file.
    """
    lock_file_path = os.path.join(root, BUCK_LOCK_FILE_NAME)
    lock_file = None
    if os.path.exists(lock_file_path):
        lock_file = open(lock_file_path, "a+")
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
            except (IOError, OSError):
                # Ignore errors like shutil.rmtree
                pass
    try:
        os.rmdir(root)
    except (IOError, OSError):
        # Ignore errors like shutil.rmtree
        pass
    if lock_file is not None:
        lock_file.close()
