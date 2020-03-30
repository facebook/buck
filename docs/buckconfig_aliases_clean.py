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

import contextlib
import difflib
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

import pkg_resources


@contextlib.contextmanager
def named_temp_file(suffix=""):
    temp = None
    try:
        temp = tempfile.NamedTemporaryFile(delete=False, suffix=suffix)
        temp.close()
        yield temp.name
    finally:
        if temp:
            os.unlink(temp.name)


@contextlib.contextmanager
def resource(name, suffix=""):
    with named_temp_file(suffix=suffix) as fout_name:
        with pkg_resources.resource_stream(__name__, name) as fin:
            with open(fout_name, "wb") as fout:
                shutil.copyfileobj(fin, fout)
        yield fout_name


class AliasesClean(unittest.TestCase):
    def test_rebuilding_does_not_generate_new_content(self):

        with resource("__buckconfig_common.soy") as buckconfig_common, resource(
            "files-and-dirs/buckconfig.soy"
        ) as buckconfig, resource(
            "generate_buckconfig_aliases", suffix=".pex"
        ) as script, named_temp_file() as temp_out:
            subprocess.check_call(
                [
                    sys.executable,
                    script,
                    "--buckconfig",
                    buckconfig,
                    "--buckconfig-aliases",
                    buckconfig_common,
                    "--buckconfig-aliases-dest",
                    temp_out,
                ]
            )
            with open(temp_out, "r") as actual_in, open(
                buckconfig_common, "r"
            ) as expected_in:
                actual = actual_in.read()
                expected = expected_in.read()

                if actual != expected:
                    diff = "\n".join(
                        difflib.context_diff(expected.splitlines(), actual.splitlines())
                    )
                    raise AssertionError(
                        "Expected unchanged buckconfig_common. Got\n{}".format(diff)
                    )
