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
import os
import tempfile
import shutil
from subprocutils import which

from buck_tool import BuckToolException, CommandLineArgs, get_java_path


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


class TestJavaPath(unittest.TestCase):
    def setUp(self):
        self.java_home = tempfile.mkdtemp()
        self.java_exec = 'java.exe' if os.name == 'nt' else 'java'
        bin_dir = os.path.join(self.java_home, 'bin')
        os.mkdir(bin_dir)
        open(os.path.join(bin_dir, self.java_exec), 'w')

    def test_with_java_home_valid(self):
        os.environ['JAVA_HOME'] = self.java_home
        self.assertEqual(get_java_path().lower(), os.path.join(
            self.java_home, 'bin', self.java_exec).lower())

    def test_with_java_home_invalid(self):
        os.environ['JAVA_HOME'] = '/nosuchfolder/89aabebc-42cb-4cd8-bcf7-d964371daf3e'
        self.assertRaises(BuckToolException)

    def test_without_java_home(self):
        self.assertEquals(get_java_path().lower(), which('java').lower())

    def tearDown(self):
        if 'JAVA_HOME' in os.environ:
            del os.environ['JAVA_HOME']
        shutil.rmtree(self.java_home)


if __name__ == '__main__':
    unittest.main()
