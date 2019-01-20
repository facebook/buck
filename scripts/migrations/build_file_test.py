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

import os
import tempfile
import unittest

import build_file
import repository


class BuildFileTest(unittest.TestCase):
    def test_find_all_include_defs_when_no_includes(self):
        self.assertEqual([], build_file.from_content("").find_all_include_defs())

    def test_find_all_include_defs_when_they_exist(self):
        all_include_defs = build_file.from_content(
            'include_defs("//foo/DEFS")'
        ).find_all_include_defs()
        self.assertEqual(
            ["//foo/DEFS"],
            list(map(lambda include: include.get_location(), all_include_defs)),
        )

    def test_find_only_include_defs_when_they_exist(self):
        content = """
include_defs("//foo/DEFS")
foo("bar")
        """
        all_include_defs = build_file.from_content(content).find_all_include_defs()
        self.assertEqual(
            ["//foo/DEFS"],
            list(map(lambda include: include.get_location(), all_include_defs)),
        )

    def test_find_used_symbols(self):
        content = """
foo = BAR
bar('foo')
def func():
  pass
"""
        self.assertEqual(
            ["BAR", "bar"],
            list(
                map(
                    lambda n: n.id,
                    build_file.from_content(content)._find_used_symbols(),
                )
            ),
        )

    def test_find_exported_symbols(self):
        content = """
foo = BAR
bar('foo')
def func():
  pass
class Clazz:
  def foo():
    pass
"""
        self.assertEqual(
            ["foo", "func", "Clazz"],
            build_file.from_content(content).find_exported_symbols(),
        )

    def test_find_export_transitive_closure(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            package_dir = os.path.join(tmp_dir, "pkg")
            os.mkdir(package_dir)
            build_file_path = os.path.join(package_dir, "BUCK")
            with open(build_file_path, "w") as buck_file:
                buck_file.write('include_defs("cell//pkg/DEFS")')
                buck_file.write(os.linesep)
                buck_file.write('foo = "FOO"')
            with open(os.path.join(package_dir, "DEFS"), "w") as defs_file:
                defs_file.write('bar = "BAR"')
            repo = repository.Repository("/repo", {"cell": tmp_dir})
            self.assertEqual(
                ["foo", "bar"],
                build_file.from_path(
                    build_file_path
                ).get_exported_symbols_transitive_closure(repo),
            )

    def test_find_function_calls_by_name(self):
        content = """
foo('bar')
bar('foo')
foo('baz')
        """
        foo_calls = build_file.from_content(content)._find_all_function_calls_by_name(
            "foo"
        )
        self.assertEqual(2, len(foo_calls))
        for call in foo_calls:
            self.assertEqual("foo", call.func.id)

    def test_get_export_map(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            package_dir = os.path.join(tmp_dir, "pkg")
            os.mkdir(package_dir)
            build_file_path = os.path.join(package_dir, "BUCK")
            with open(build_file_path, "w") as buck_file:
                buck_file.write('include_defs("cell//pkg/DEFS")')
                buck_file.write(os.linesep)
                buck_file.write('foo = "FOO"')
            with open(os.path.join(package_dir, "DEFS"), "w") as defs_file:
                defs_file.write('include_defs("cell//pkg/DEFS2")')
                defs_file.write(os.linesep)
                defs_file.write('bar = "BAR"')
            with open(os.path.join(package_dir, "DEFS2"), "w") as defs_file:
                defs_file.write('baz = "BAZ"')
            repo = repository.Repository("/repo", {"cell": tmp_dir})
            self.assertEqual(
                {"cell//pkg/DEFS": ["bar"], "cell//pkg/DEFS2": ["baz"]},
                build_file.from_path(build_file_path).get_export_map(repo),
            )
