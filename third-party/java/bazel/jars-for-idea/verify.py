#!/usr/bin/env python3

# Verify prebuilt starlark-annot-processor jars are
# up to date (built using current sources).

import os
import unittest
import hashlib

class TestSourcesSha1(unittest.TestCase):
    def test_consistent(self):
        sources_path = os.getenv("STARLARK_ANNOT_SOURCES")
        sources_sha1_path = os.getenv("STARLARK_ANNOT_SOURCES_SHA1")
        assert sources_path
        assert sources_sha1_path

        with open(sources_sha1_path) as f:
            sources_sha1_expected = f.readline().strip()

        java_files = []

        for dirpath, dirnames, filenames in os.walk(sources_path):
            java_files.extend([dirpath + "/" + f for f in filenames if f.endswith(".java")])

        java_files = sorted(java_files)
        java_files_concatenated = b""
        for j in java_files:
            with open(j, mode="rb") as jf:
                java_files_concatenated += jf.read()

        sources_sha1 = hashlib.sha1(java_files_concatenated).hexdigest()
        assert sources_sha1 == sources_sha1_expected, "jars-for-idea/gen.sh to regenerate idea jars"
