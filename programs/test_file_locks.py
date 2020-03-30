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

import itertools
import os
import random
import shutil
import tempfile
import time
import unittest
from multiprocessing import Pool, Process, Queue

from programs import file_locks


PROCESS_1 = "supercalifragilisticexpialidocious"
PROCESS_2 = "donaudampfschifffahrtsgesellschaftskapitaenskajuetenremix"

# Python will gc the lock files otherwise
leak_files_please = []


def acquire_shared_lock(path):
    fh = open(path, "a+")  # noqa: P201
    leak_files_please.append(fh)
    return file_locks.acquire_shared_lock(fh)


def acquire_exclusive_lock(path):
    fh = open(path, "a+")  # noqa: P201
    leak_files_please.append(fh)
    return file_locks.acquire_exclusive_lock(fh)


def append_test_array(path, queue, label, exclusive, *args, **kwargs):
    with open(path, "a+") as f:
        if exclusive:
            file_locks.acquire_exclusive_lock(f, *args, **kwargs)
        else:
            file_locks.acquire_shared_lock(f, *args, **kwargs)
        for letter in label:
            time.sleep(random.uniform(0.0, 0.01))
            queue.put(letter)
    queue.close()


def check_test_labels(string, labels):
    for perm in itertools.permutations(labels):
        if string == "".join(perm):
            return True
    return False


# All functions used by other_process must be defined before this call
other_process = Pool(processes=1)


@unittest.skipIf(os.name != "posix", "Only works on posix")
class TestFileLocks(unittest.TestCase):
    def setUp(self):
        self.tmpdir = tempfile.mkdtemp()

    def tearDown(self):
        shutil.rmtree(self.tmpdir)

    # process_map = { 'process_label' : exclusive_lock }
    def helper_acquire_wait(self, process_map, should_fail=False, msg=None, attempts=1):
        lockfile = os.path.join(self.tmpdir, "l")
        labels_list_str = ",".join(process_map.keys())

        # allow retries becaue of the stochastic nature of some expected results
        while attempts > 0:
            queue = Queue()
            labels = list(process_map.keys())
            random.shuffle(labels)
            processes = []
            for label in labels:
                processes.append(
                    Process(
                        target=append_test_array,
                        args=[lockfile, queue, label, process_map[label]],
                        kwargs={"wait": True},
                    )
                )
            for process in processes:
                process.start()
            for process in processes:
                process.join()

            string = ""
            while not queue.empty():
                c = queue.get()
                string += c
            if len(string) != sum(map(len, labels)):
                self.fail(
                    msg="%s differs in length from sequence of %s"
                    % (string, labels_list_str)
                )

            if check_test_labels(string, labels) != should_fail:
                return True

            attempts -= 1

        self.fail(
            msg="%s %s equal to a permutation of %s"
            % (string, "is" if should_fail else "is not", labels_list_str)
        )

    def test_acquire_shared_twice_same_process(self):
        lock = os.path.join(self.tmpdir, "lock")
        self.assertTrue(acquire_shared_lock(lock))
        self.assertTrue(acquire_shared_lock(lock))

    def test_acquire_shared_twice_multi_process(self):
        lock = os.path.join(self.tmpdir, "lock")
        self.assertTrue(acquire_shared_lock(lock))
        self.assertTrue(other_process.apply(acquire_shared_lock, [lock]))

    def test_acquire_shared_exclusive_same_process(self):
        lock = os.path.join(self.tmpdir, "lock")
        self.assertTrue(acquire_shared_lock(lock))
        self.assertTrue(acquire_exclusive_lock(lock))

    def test_acquire_shared_exclusive_multi_process(self):
        lock = os.path.join(self.tmpdir, "lock")
        self.assertTrue(acquire_shared_lock(lock))
        self.assertFalse(other_process.apply(acquire_exclusive_lock, [lock]))

    def test_acquire_exclusive_shared_multi_process(self):
        lock = os.path.join(self.tmpdir, "lock")
        self.assertTrue(acquire_exclusive_lock(lock))
        self.assertFalse(other_process.apply(acquire_shared_lock, [lock]))

    def test_acquire_exclusive_exclusive_wait_multi_process(self):
        self.helper_acquire_wait({PROCESS_1: True, PROCESS_2: True})

    def test_acquire_exclusive_shared_wait_multi_process(self):
        self.helper_acquire_wait({PROCESS_1: True, PROCESS_2: False})

    def test_acquire_shared_shared_wait_multi_process(self):
        self.helper_acquire_wait(
            {PROCESS_1: False, PROCESS_2: False}, should_fail=True, attempts=3
        )

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
