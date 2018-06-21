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

import os
import shutil
import tempfile
import unittest
from multiprocessing import Pool

import file_locks

# Python will gc the lock files otherwise
leak_files_please = []


def acquire_shared_lock(path):
    f = open(path, "a+")
    leak_files_please.append(f)
    return file_locks.acquire_shared_lock(f)


def acquire_exclusive_lock(path):
    f = open(path, "a+")
    leak_files_please.append(f)
    return file_locks.acquire_exclusive_lock(f)


# All functions used by other_process must be defined before this call
other_process = Pool(processes=1)


@unittest.skipIf(os.name != "posix", "Only works on posix")
class TestFileLocks(unittest.TestCase):
    def setUp(self):
        self.tmpdir = tempfile.mkdtemp()

    def tearDown(self):
        shutil.rmtree(self.tmpdir)

    def test_acquire_shared_twice_same_process(self):
        l = os.path.join(self.tmpdir, "l")
        self.assertTrue(acquire_shared_lock(l))
        self.assertTrue(acquire_shared_lock(l))

    def test_acquire_shared_twice_multi_process(self):
        l = os.path.join(self.tmpdir, "l")
        self.assertTrue(acquire_shared_lock(l))
        self.assertTrue(other_process.apply(acquire_shared_lock, [l]))

    def test_acquire_shared_exclusive_same_process(self):
        l = os.path.join(self.tmpdir, "l")
        self.assertTrue(acquire_shared_lock(l))
        self.assertTrue(acquire_exclusive_lock(l))

    def test_acquire_shared_exclusive_multi_process(self):
        l = os.path.join(self.tmpdir, "l")
        self.assertTrue(acquire_shared_lock(l))
        self.assertFalse(other_process.apply(acquire_exclusive_lock, [l]))

    def test_acquire_exclusive_shared_multi_process(self):
        l = os.path.join(self.tmpdir, "l")
        self.assertTrue(acquire_exclusive_lock(l))
        self.assertFalse(other_process.apply(acquire_shared_lock, [l]))

    def test_rmtree_if_can_lock(self):
        def resolve(path):
            return os.path.join(self.tmpdir, path)

        lock_file = "top/locked/" + file_locks.BUCK_LOCK_FILE_NAME
        keep_file = "top/locked/keep"
        unlocked_delete_file = "top/unlocked/delete"
        regular_delete_file = "top/regular/delete"
        for d in ["top", "top/locked", "top/unlocked", "top/regular"]:
            os.mkdir(resolve(d))
        for f in [
            "top/.buckd",
            keep_file,
            lock_file,
            "top/unlocked/" + file_locks.BUCK_LOCK_FILE_NAME,
            unlocked_delete_file,
            regular_delete_file,
        ]:
            open(resolve(f), "a+").close()
        acquire_exclusive_lock(resolve(lock_file))
        other_process.apply(file_locks.rmtree_if_can_lock, [resolve("top")])
        self.assertTrue(os.path.exists(resolve(keep_file)))
        self.assertFalse(os.path.exists(resolve(unlocked_delete_file)))
        self.assertFalse(os.path.exists(resolve(regular_delete_file)))


if __name__ == "__main__":
    unittest.main()
