# Copyright (c) Meta Platforms, Inc. and its affiliates.
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
import glob
import os
import unittest

from dependency.repo import Repo


class BaseTestCase(unittest.TestCase):
    @classmethod
    def setUpClass(self):
        self.repo = Repo("../third-party")
        self.deps_file = "data/deps.txt"

    @classmethod
    def tearDownClass(self):
        print(f"cleaning up the {self.repo.get_libs_name()}")
        files = glob.glob(f"{self.repo.get_libs_name()}/**/*.*", recursive=True)
        for f in files:
            try:
                if os.path.isfile(f):
                    os.remove(f)
            except OSError as e:
                self.fail(e)
