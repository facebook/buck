#!/usr/bin/env python
#
# This script runs the plovr build to make sure none of the soy files
# have syntax errors.

import json
import os
import subprocess
import tempfile
import unittest

PLOVR_JAR = "plovr-81ed862.jar"


def find_docs_dir():
    cwd = os.path.dirname(os.path.realpath(__file__))
    prev_cwd = None
    while cwd != prev_cwd:
        if os.path.exists(os.path.join(cwd, "docs", PLOVR_JAR)):
            return os.path.join(cwd, "docs")
        prev_cwd = cwd
        cwd = os.path.dirname(cwd)
    raise Exception("Could not find plovr jar")


def main():
    docs_dir = find_docs_dir()
    input_files = []
    for root, dirs, files in os.walk(docs_dir):
        for file_name in files:
            if file_name.endswith(".soy") and not file_name.startswith("__"):
                input_files.append(os.path.join(root, file_name))
    print("Building %s soy files." % len(input_files))
    config_data = {
        "id": "buck",
        # This removes the warning about param being a reserved JS keyword.
        "experimental-compiler-options": {"languageIn": "ECMASCRIPT5"},
        "paths": docs_dir,
        "inputs": input_files,
    }

    plovr_abspath = os.path.join(docs_dir, PLOVR_JAR)
    fd, config_path = tempfile.mkstemp(suffix=".json", prefix="plovr_config")
    try:
        with open(config_path, "w") as config_file:
            json.dump(config_data, config_file)
        subprocess.check_output(["java", "-jar", plovr_abspath, "build", config_path])
    finally:
        os.close(fd)
        os.unlink(config_path)


class TestBuckPackage(unittest.TestCase):
    def test_no_soy_doc_syntax_error(self):
        main()


if __name__ == "__main__":
    unittest.main()
