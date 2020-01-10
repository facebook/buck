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

import os
import shutil
import tempfile
import unittest

from programs import buck_project


class TestBuckProjects(unittest.TestCase):
    def test_makedirs(self):
        # We unfortunately cannot use tempfile.TemporaryDirectory() here
        # since `buck test` still runs this test using Python 2.x
        tmpdir = tempfile.mkdtemp()
        try:
            foo_bar = os.path.join(tmpdir, "foo", "bar")
            # makedirs() can create intermediate directories
            buck_project.makedirs(foo_bar)
            # With called on an existing directory it should succeed
            buck_project.makedirs(foo_bar)

            # It should raise an error if it fails
            test_txt = os.path.join(tmpdir, "test.txt")
            with open(test_txt, "w") as f:
                f.write("foo\n")

            # makedirs() should raise an error if it fails
            with self.assertRaises(OSError):
                # EEXIST, but for a non-directory file
                buck_project.makedirs(test_txt)
            with self.assertRaises(OSError):
                # ENOTDIR
                buck_project.makedirs(os.path.join(test_txt, "foo"))
        finally:
            shutil.rmtree(tmpdir)


if __name__ == "__main__":
    unittest.main()
