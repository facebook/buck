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

import platform
import unittest

from buck_package import *


class TestBuckPackage(unittest.TestCase):
    def test_does_not_crash_on_file_removed(self):
        if platform.system() == "Windows":
            # Deleting open files on Windows fails.
            return
        with closable_named_temporary_file() as outf:
            os.remove(outf.name)


if __name__ == "__main__":
    unittest.main()
