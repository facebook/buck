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

import ast
import unittest

import include_def
import repository


class IncludeDefTest(unittest.TestCase):
    def test_can_get_location_from_ast_call(self):
        include = self._parse_include_def('include_defs("//foo/DEFS")')
        self.assertEqual("//foo/DEFS", include.get_location())

    def test_can_get_include_path(self):
        repo = repository.Repository("/repo", {"cell": "/repo/cell"})
        include = self._parse_include_def('include_defs("cell//pkg/DEFS")')
        self.assertEqual("/repo/cell/pkg/DEFS", include.get_include_path(repo))

    def test_handles_dash_in_path(self):
        repo = repository.Repository("/repo", {})
        include = self._parse_include_def('include_defs("//third-party/DEFS")')
        self.assertEqual("//third-party/DEFS", include.get_location())
        self.assertEqual("/repo/third-party/DEFS", include.get_include_path(repo))

    def _parse_include_def(self, code: str) -> include_def.IncludeDef:
        return include_def.from_ast_call(ast.parse(code).body[0].value)
