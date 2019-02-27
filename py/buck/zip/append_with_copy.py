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

""" Appends a file to a zip archive with copying the resulting zip to a new place """

import argparse
import shutil
import sys
from zipfile import ZipFile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input")
    parser.add_argument("--output")
    parser.add_argument(
        "files-to-append",
        nargs="+",
        help="Pairs of new zip entry name and file with entry content",
    )
    options = parser.parse_args()
    copy_and_append_to_zip_file(options.input, options.output, options.files_to_append)


def copy_and_append_to_zip_file(input_zip_file, output_zip_file, files_to_append):
    shutil.copy(input_zip_file, output_zip_file)
    append_files_to_zip(output_zip_file, files_to_append)


def append_files_to_zip(zip_file_name, files_to_append):
    with ZipFile(zip_file_name, "a") as zip_file:
        for i in range(0, len(files_to_append) - 1, 2):
            entry_name = files_to_append[i]
            entry_content = __read_file(files_to_append[i + 1])
            zip_file.writestr(entry_name, entry_content)


def __read_file(file_path):
    with open(file_path) as new_file:
        return new_file.read()


if __name__ == "__main__":
    copy_and_append_to_zip_file(sys.argv[1], sys.argv[2], sys.argv[3:])
