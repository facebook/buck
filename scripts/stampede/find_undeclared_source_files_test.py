#!/usr/bin/python
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
import shutil
import tempfile
import unittest

from find_undeclared_source_files import Paths, process_stampede, process_strace


############################################################
# Util Functions
############################################################
def read_lines(abs_path):
    with open(abs_path) as fp:
        return [line.strip() for line in fp.readlines()]


def write_lines(abs_path, lines):
    with open(abs_path, "w") as fp:
        return fp.writelines([l + "\n" for l in lines])


def strace_line(path):
    return '2624304 open("{}", O_RDONLY) = 3'.format(path)


def write_strace_file(abs_path, paths):
    write_lines(abs_path, [strace_line(path) for path in paths])


def touch(abs_path):
    write_lines(abs_path, [])
    return abs_path


############################################################
# Test Classes
############################################################
class TempDirBaseTestCase(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="find_undeclared_source_files_test")
        self.paths = Paths(self.dir)

    def tearDown(self):
        shutil.rmtree(self.dir)


class TestStraceProcessing(TempDirBaseTestCase):
    def test_non_existent_not_included(self):
        abs_path = os.path.join(self.dir, "hello.txt")
        self.assertFalse(os.path.exists(abs_path))
        write_strace_file(self.paths.strace_raw_file, [abs_path])
        process_strace(
            self.paths.strace_raw_file,
            self.paths.strace_processed_file,
            includes=[],
            known_roots=[],
        )
        lines = read_lines(self.paths.strace_processed_file)
        self.assertEqual(0, len(lines))

    def test_relative_path(self):
        name = "cool.txt"
        touch(os.path.join(self.dir, name)),
        write_strace_file(self.paths.strace_raw_file, [name])
        process_strace(
            self.paths.strace_raw_file,
            self.paths.strace_processed_file,
            includes=[],
            known_roots=[self.dir],
        )
        lines = read_lines(self.paths.strace_processed_file)
        self.assertEqual(1, len(lines))

    def test_absolute_path(self):
        abs_path = touch(os.path.join(self.dir, "nice.txt"))
        self.assertTrue(os.path.isabs(abs_path))
        write_strace_file(self.paths.strace_raw_file, [abs_path])
        process_strace(
            self.paths.strace_raw_file,
            self.paths.strace_processed_file,
            includes=[],
            known_roots=[],
        )
        lines = read_lines(self.paths.strace_processed_file)
        self.assertEqual(1, len(lines))


class TestStampedeProcessing(TempDirBaseTestCase):
    def test_process_empty_includes(self):
        original_lines = [
            touch(os.path.join(self.dir, "a.txt")),
            touch(os.path.join(self.dir, "b.txt")),
        ]
        write_lines(self.paths.stampede_raw_file, original_lines)
        process_stampede(
            self.paths.stampede_raw_file, self.paths.stampede_processed_file, []
        )
        lines = read_lines(self.paths.stampede_processed_file)
        self.assertEqual(2, len(lines))

    def test_process_non_matching_includes(self):
        original_lines = [touch(os.path.join(self.dir, "a.txt"))]
        write_lines(self.paths.stampede_raw_file, original_lines)
        process_stampede(
            self.paths.stampede_raw_file,
            self.paths.stampede_processed_file,
            ["/this/does/not/exist"],
        )
        lines = read_lines(self.paths.stampede_processed_file)
        self.assertEqual(0, len(lines))

    def test_process_selective_includes(self):
        dir1 = tempfile.mkdtemp()
        dir2 = tempfile.mkdtemp()
        try:
            original_lines = [
                touch(os.path.join(dir1, "a.txt")),
                touch(os.path.join(dir2, "b.txt")),
            ]
            write_lines(self.paths.stampede_raw_file, original_lines)
            process_stampede(
                self.paths.stampede_raw_file,
                self.paths.stampede_processed_file,
                [os.path.realpath(dir2)],
            )
            lines = read_lines(self.paths.stampede_processed_file)
            self.assertEqual(1, len(lines))
        finally:
            shutil.rmtree(dir1)
            shutil.rmtree(dir2)


############################################################
# Main
############################################################
if __name__ == "__main__":
    unittest.main()
