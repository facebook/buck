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
import unittest

from programs.buck_tool import BuckToolException, CommandLineArgs, MovableTemporaryFile


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
        self.assertTrue(args.is_help())

    def test_short_help_before_command(self):
        args = CommandLineArgs(["buck", "-h", "clean"])
        self.assertEqual(args.command, "clean")
        self.assertEqual(args.buck_options, ["-h"])
        self.assertEqual(args.command_options, [])
        self.assertTrue(args.is_help())

    def test_short_help_after_command(self):
        args = CommandLineArgs(["buck", "clean", "-h"])
        self.assertEqual(args.command, "clean")
        self.assertEqual(args.buck_options, [])
        self.assertEqual(args.command_options, ["-h"])
        self.assertTrue(args.is_help())

    def test_short_help_after_external(self):
        args = CommandLineArgs(["buck", "test", "--", "-h"])
        self.assertEqual(args.command, "test")
        self.assertEqual(args.buck_options, [])
        self.assertEqual(args.command_options, [])
        self.assertFalse(args.is_help())

    def test_command_all(self):
        args = CommandLineArgs(
            ["buck", "--help", "--version", "clean", "--help", "all"]
        )
        self.assertEqual(args.command, "clean")
        self.assertEqual(args.buck_options, ["--help", "--version"])
        self.assertEqual(args.command_options, ["--help", "all"])
        self.assertTrue(args.is_help())

    def test_run_command(self):
        args = CommandLineArgs(["buck", "run", "--help"])
        self.assertEqual(args.command, "run")
        self.assertEqual(args.buck_options, [])
        self.assertEqual(args.command_options, ["--help"])
        self.assertTrue(args.is_help())

    def test_run_command_help_for_program(self):
        args = CommandLineArgs(["buck", "run", "//some:cli", "--", "--help"])
        self.assertEqual(args.command, "run")
        self.assertEqual(args.buck_options, [])
        self.assertEqual(args.command_options, ["//some:cli"])
        self.assertFalse(args.is_help())

    def test_run_command_help_for_program_and_buck(self):
        args = CommandLineArgs(["buck", "--help", "run", "//some:cli", "--", "--help"])
        self.assertEqual(args.command, "run")
        self.assertEqual(args.buck_options, ["--help"])
        self.assertEqual(args.command_options, ["//some:cli"])
        self.assertTrue(args.is_help())

    def test_run_command_help_for_program_and_command(self):
        args = CommandLineArgs(["buck", "run", "--help", "//some:cli", "--", "--help"])
        self.assertEqual(args.command, "run")
        self.assertEqual(args.buck_options, [])
        self.assertEqual(args.command_options, ["--help", "//some:cli"])
        self.assertTrue(args.is_help())


class TestMovableTemporaryFile(unittest.TestCase):
    def test_cleans_up_if_not_moved(self):
        path = None
        with MovableTemporaryFile() as f:
            f.close()
            path = f.name
            self.assertTrue(os.path.exists(path))

        self.assertIsNotNone(path)
        self.assertFalse(os.path.exists(path))

    def test_leaves_file_if_moved(self):
        path = None
        moved = None
        with MovableTemporaryFile() as f:
            f.close()
            path = f.name
            self.assertTrue(os.path.exists(path))
            moved = f.move()

        try:
            self.assertIsNotNone(path)
            self.assertIsNotNone(moved)
            self.assertTrue(os.path.exists(path))
        finally:
            if path and os.path.exists(path):
                os.unlink(path)

    def test_cleans_up_if_moved_context_is_entered(self):
        path = None
        moved = None

        path = None
        moved = None
        with MovableTemporaryFile() as f:
            f.close()
            path = f.name
            self.assertTrue(os.path.exists(path))
            moved = f.move()

        try:
            with moved as f2:
                self.assertEquals(path, f2.file.name)
                self.assertTrue(os.path.exists(path))
            self.assertFalse(os.path.exists(path))
        finally:
            if path and os.path.exists(path):
                os.unlink(path)

    def test_close_and_name(self):
        with MovableTemporaryFile() as f:
            self.assertFalse(f.file.closed)
            f.close()
            self.assertTrue(f.file.closed)
            self.assertEquals(f.file.name, f.name)

    def test_handles_file_going_missing_while_entered(self):
        path = None

        with MovableTemporaryFile() as f:
            f.close()
            path = f.name
            self.assertTrue(os.path.exists(path))
            os.unlink(path)
        self.assertFalse(os.path.exists(path))


if __name__ == "__main__":
    unittest.main()
