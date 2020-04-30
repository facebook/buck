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

import os
import re
import unittest


class TestBuckWrapperInfo(unittest.TestCase):
    WRAPPER_INFO_DIR = "programs"
    WRAPPER_INFO_FILE = "buck_wrapper_info.txt"
    FORMAT = re.compile(
        rb"""
        ^
        (?:
            ~\w+~    # flag_name
            [ -}\t]* # wsp + visible without ~
            \n       # must always end with a newline
        )*
        $
        """,
        re.VERBOSE,
    )

    def test_buck_wrapper_info_valid(self):
        file_name = os.path.join(
            os.getcwd(), self.WRAPPER_INFO_DIR, self.WRAPPER_INFO_FILE
        )
        with open(file_name, "rb") as fh:
            self.assertRegex(fh.read(), self.FORMAT)


if __name__ == "__main__":
    unittest.main()
