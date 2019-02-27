#!/usr/bin/env python
# Copyright 2017-present Facebook, Inc.
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

import os.path
import shutil
import tempfile
import unittest
import uuid
import zipfile

from py.buck.zip import append_with_copy


class TestAppend(unittest.TestCase):
    def setUp(self):
        self.test_dir = tempfile.mkdtemp()

    def tearDown(self):
        shutil.rmtree(self.test_dir)

    def test_file_included(self):
        src_zip_name = "src.zip"
        src_zip_path = os.path.join(self.test_dir, src_zip_name)

        existing_entry = "existing_entry"
        existing_entry_content = uuid.uuid4().hex
        self.__create_zip_file(src_zip_path, existing_entry, existing_entry_content)

        appended_entry_name = "appended_entry"
        appended_file_name = "appended_file"
        appended_file_path = os.path.join(self.test_dir, appended_file_name)
        appended_file_content = uuid.uuid4().hex
        self.__create_file(appended_file_path, appended_file_content)

        dst_file_path = os.path.join(self.test_dir, "dst.zip")

        append_with_copy.copy_and_append_to_zip_file(
            src_zip_path, dst_file_path, [appended_entry_name, appended_file_path]
        )

        self.assertEqual(
            existing_entry_content,
            self.__read_file_entry(dst_file_path, existing_entry),
        )
        self.assertEqual(
            appended_file_content,
            self.__read_file_entry(dst_file_path, appended_entry_name),
        )

    def __create_zip_file(self, zip_file_path, entry, entry_content):
        with zipfile.ZipFile(zip_file_path, "w") as zip_file:
            zip_file.writestr(entry, entry_content)

    def __create_file(self, file_path, file_content):
        with open(file_path, "w") as new_file:
            new_file.write(file_content)

    def __read_file_entry(self, zip_file_path, entry_name):
        with zipfile.ZipFile(zip_file_path) as zip_file:
            return zip_file.read(entry_name)
