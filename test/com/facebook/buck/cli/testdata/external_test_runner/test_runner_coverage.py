#!/usr/bin/python -u
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

from __future__ import print_function

import json
import optparse
import subprocess
import sys

parser = optparse.OptionParser()
parser.add_option("--buck-test-info")
parser.add_option("--jobs", type=int)
(options, args) = parser.parse_args()


def join_paths(paths):
    return "[" + ", ".join(["'" + f.replace("\\", "\\\\") + "'" for f in paths]) + "]"


def convert_coverage_to_str(coverage):
    return "[" + repr(coverage[0]) + ", " + join_paths(coverage[1]) + "]"


def convert_coverage_entries_to_str(coverage_entries):
    return "[" + ", ".join([convert_coverage_to_str(c) for c in coverage_entries]) + "]"


with open(options.buck_test_info) as f:
    test_infos = json.load(f)
    coverage = test_infos[0]["needed_coverage"]
    print(convert_coverage_entries_to_str(coverage))
