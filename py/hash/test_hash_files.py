# Copyright 2015-present Facebook, Inc.
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


import os.path
import shutil
import tempfile
import unittest

from .hash_files import hash_files


class TestHashFiles(unittest.TestCase):
    def setUp(self):
        self.test_dir = tempfile.mkdtemp()

    def tearDown(self):
        # Remove the directory after the test
        shutil.rmtree(self.test_dir)

    def __write_to_file(self, filename, content):
        with open(filename, "w") as input_file:
            input_file.write(content)

    def test_hash_file(self):
        file_to_hash_name = os.path.join(self.test_dir, "file_to_hash")
        self.__write_to_file(file_to_hash_name, "Some text to hash")

        self.assertEquals(
            "3eecc85e6440899b28a9ea6d8369f01c", hash_files([file_to_hash_name])
        )

    def test_hash_multiple_file(self):
        files_to_hash = []
        for i in range(1, 5):
            file_to_hash_name = os.path.join(self.test_dir, "file_to_hash_%s" % i)
            self.__write_to_file(file_to_hash_name, "Some text to hash")
            files_to_hash.append(file_to_hash_name)

        self.assertEquals("93d3c1c8adf801b7bb80b37ffeb73965", hash_files(files_to_hash))
