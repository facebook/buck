# Copyright 2018-present Facebook, Inc.
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

import unittest

import repository


class RepositoryTest(unittest.TestCase):
    def test_can_get_path_to_cell(self):
        repo = repository.Repository("/root", {"cell": "/cell"})
        self.assertEqual("/cell", repo.get_cell_path("cell"))

    def test_can_get_path_to_default_cell(self):
        repo = repository.Repository("/root", {"cell": "/cell"})
        self.assertEqual("/root", repo.get_cell_path(""))
