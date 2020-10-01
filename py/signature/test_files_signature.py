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


import os.path
import shutil
import tempfile
import unittest

from .files_signature import digests_to_str, signature


class TestFileSignature(unittest.TestCase):
    def setUp(self):
        self.test_dir = tempfile.mkdtemp()

    def tearDown(self):
        # Remove the directory after the test
        shutil.rmtree(self.test_dir)

    def __write_to_file(self, filename, content):
        with open(filename, "w") as input_file:
            input_file.write(content)

    def test_file_signature(self):
        file_to_sign_name = os.path.join(self.test_dir, "file_to_hash")
        self.__write_to_file(file_to_sign_name, "Some text to hash")

        self.assertEquals(
            [
                "f128aafc1b40b92bbaa462087fef22a7",
                digests_to_str({"name": "3eecc85e6440899b28a9ea6d8369f01c"}),
            ],
            signature([("name", file_to_sign_name)]),
        )

    def test_hash_multiple_file(self):
        files_to_sign = []
        for i in range(1, 5):
            file_to_sign_name = os.path.join(self.test_dir, "file_to_hash_%s" % i)
            self.__write_to_file(file_to_sign_name, "Some text to hash")
            files_to_sign.append(("name%d" % i, file_to_sign_name))
        files_to_sign.append(("full_dir", self.test_dir))

        self.assertEquals(
            [
                "4134dd04aa49aa4745a8989bcc68020b",
                digests_to_str(
                    {
                        "full_dir": {
                            "file_to_hash_1": "3eecc85e6440899b28a9ea6d8369f01c",
                            "file_to_hash_2": "3eecc85e6440899b28a9ea6d8369f01c",
                            "file_to_hash_3": "3eecc85e6440899b28a9ea6d8369f01c",
                            "file_to_hash_4": "3eecc85e6440899b28a9ea6d8369f01c",
                        },
                        "name1": "3eecc85e6440899b28a9ea6d8369f01c",
                        "name2": "3eecc85e6440899b28a9ea6d8369f01c",
                        "name3": "3eecc85e6440899b28a9ea6d8369f01c",
                        "name4": "3eecc85e6440899b28a9ea6d8369f01c",
                    }
                ),
            ],
            signature(files_to_sign),
        )
