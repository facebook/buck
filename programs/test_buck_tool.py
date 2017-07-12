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

import unittest

from buck_tool import CommandLineArgs


class TestCommandLineArgs(unittest.TestCase):
    def test_empty_command(self):
        args = CommandLineArgs(["buck"])
        self.assertEqual(args.command, None)
        self.assertEqual(args.buck_options, [])
        self.assertEqual(args.command_options, [])
        self.assertTrue(args.is_help(), "With no arguments should show help")

    def test_single_command(self):
        args = CommandLineArgs(["buck", "clean"])
        self.assertEqual(args.command, "clean")
        self.assertEqual(args.buck_options, [])
        self.assertEqual(args.command_options, [])
        self.assertFalse(args.is_help())

    def test_global_short_help(self):
        args = CommandLineArgs(["buck", "-h"])
        self.assertEqual(args.command, None)
        self.assertEqual(args.buck_options, ["-h"])
        self.assertEqual(args.command_options, [])
        self.assertTrue(args.is_help())

    def test_global_help(self):
        args = CommandLineArgs(["buck", "--help"])
        self.assertEqual(args.command, None)
        self.assertEqual(args.buck_options, ["--help"])
        self.assertEqual(args.command_options, [])
        self.assertTrue(args.is_help())

    def test_global_version(self):
        args = CommandLineArgs(["buck", "--version"])
        self.assertEqual(args.command, None)
        self.assertEqual(args.buck_options, ["--version"])
        self.assertEqual(args.command_options, [])
        self.assertTrue(args.is_help(), "--version does not require a build")
        self.assertTrue(args.is_version())

    def test_command_help(self):
        args = CommandLineArgs(["buck", "clean", "--help"])
        self.assertEqual(args.command, "clean")
        self.assertEqual(args.buck_options, [])
        self.assertEqual(args.command_options, ["--help"])
        self.assertTrue(args.is_help())

    def test_help_command(self):
        args = CommandLineArgs(["buck", "--help", "clean"])
        self.assertEqual(args.command, "clean")
        self.assertEqual(args.buck_options, ["--help"])
        self.assertEqual(args.command_options, [])
        self.assertFalse(args.is_help(), "Global --help ignored with command")

    def test_command_all(self):
        args = CommandLineArgs(["buck", "--help", "--version", "clean", "--help", "all"])
        self.assertEqual(args.command, "clean")
        self.assertEqual(args.buck_options, ["--help", "--version"])
        self.assertEqual(args.command_options, ["--help", "all"])
        self.assertTrue(args.is_help())


if __name__ == '__main__':
    unittest.main()
