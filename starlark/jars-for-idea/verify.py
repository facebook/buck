#!/usr/bin/env python3
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

# Verify prebuilt starlark-annot-processor jars are
# up to date (built using current sources).

import hashlib
import os
import unittest


class TestSourcesSha1(unittest.TestCase):
    def test_consistent(self):
        sources_path = os.getenv("STARLARK_ANNOT_SOURCES")
        sources_sha1_path = os.getenv("STARLARK_ANNOT_SOURCES_SHA1")
        assert sources_path
        assert sources_sha1_path

        with open(sources_sha1_path) as f:
            sources_sha1_expected = f.readline().strip()

        java_files = []

        for dirpath, _dirnames, filenames in os.walk(sources_path):
            java_files.extend(
                [dirpath + "/" + f for f in filenames if f.endswith(".java")]
            )

        java_files = sorted(java_files)
        java_files_concatenated = b""
        for j in java_files:
            with open(j, mode="rb") as jf:
                java_files_concatenated += jf.read()

        sources_sha1 = hashlib.sha1(java_files_concatenated).hexdigest()
        assert (
            sources_sha1 == sources_sha1_expected
        ), "jars-for-idea/gen.sh to regenerate idea jars"
