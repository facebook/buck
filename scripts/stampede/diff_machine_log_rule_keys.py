#! /usr/bin/python
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


from __future__ import absolute_import, division, print_function, unicode_literals

import argparse
import collections
import json
import os
import re


class BuildRuleDiffResult(object):
    def __init__(self, build_rule_name, build_rules, rule_key_type, was_match):
        self.build_rule_name = build_rule_name
        self.build_rules = build_rules
        self.rule_key_type = rule_key_type
        self.was_match = was_match


class LogFileDiffResult(object):
    def __init__(
        self,
        log_file_one,
        log_file_two,
        extra_build_rules_by_log_file,
        matching_results_by_rule_key_type,
        mismatching_results_by_rule_key_type,
    ):
        self.log_file_one = log_file_one
        self.log_file_two = log_file_two
        self.extra_build_rules_by_log_file = extra_build_rules_by_log_file
        self.matching_results_by_rule_key_type = matching_results_by_rule_key_type
        self.mismatching_results_by_rule_key_type = mismatching_results_by_rule_key_type


LogFile = collections.namedtuple("LogFile", ["id", "path"])

BuildRule = collections.namedtuple(
    "BuildRule", ["name", "log_file", "default_rulekey", "input_rulekey"]
)

# Rule key type enum
DEFAULT_RULE_KEY = 1
INPUT_RULE_KEY = 2

BUILD_RULE_FINISHED_TOKEN = "BuildRuleEvent.Finished"
SUPPORTED_RULE_KEY_TYPES = [DEFAULT_RULE_KEY, INPUT_RULE_KEY]


def parseArgs():
    description = """Analyze rule key differences between two builds.
To use this tool you need to obtain run the build twice, once locally
and once distributed, and then give the tool the buck-out paths of both.

Note that this tool is not to be trusted blindly.
It will point out differences in default rule key for rules which were built or
fetched in both the builds. It will also try to spot the differences in output
hash but only for the build rules which have the `outputHash` field set in both
builds. (Buck doesn't set output hash for rules which it fetches from the
cache. So do a --no-cache build to get the output hash for every rule.)
"""
    parser = argparse.ArgumentParser(description=description)
    parser.add_argument(
        "localBuildBuckOut", type=str, help="Path to buck-out of local build"
    )
    parser.add_argument(
        "distBuildBuckOut", type=str, help="Path to buck-out of dist build client"
    )
    parser.add_argument(
        "-v",
        "--verbose",
        action="store_true",
        help="Print extra information (like cache misses).",
    )
    return parser.parse_args()


def read_file(log_file):
    build_rules = {}
    with open(log_file.path, "r") as logfile:
        for line in logfile:
            if line.startswith(BUILD_RULE_FINISHED_TOKEN):
                row = json.loads(line[len(BUILD_RULE_FINISHED_TOKEN) + 1 :])
                rule_keys = row["ruleKeys"]
                if not rule_keys.get("inputRuleKey"):
                    rule_keys["inputRuleKey"] = {"hashCode": None}

                rule = BuildRule(
                    name=row["buildRule"]["name"],
                    log_file=log_file,
                    default_rulekey=rule_keys["ruleKey"]["hashCode"],
                    input_rulekey=rule_keys["inputRuleKey"]["hashCode"],
                )
                build_rules[rule.name] = rule
    return build_rules


def print_line_sep(c):
    print(c * 80)


def list_rules_with_msg(rules, msg):
    if len(rules) > 0:
        print_line_sep("=")
        print(msg)
        print_line_sep("-")
        for rule in rules:
            print(rule)
        print_line_sep("=")


def find_dist_build_run_id(path):
    re_match = re.search(r"dist-build-slave-(.*?)/", path)
    if re_match is None:
        raise Exception("Could not find run id from path " + path)
    return re_match.group(1)


def find_dist_build_logs(path_to_buck_out):
    log_files = []
    for log_path in find_log_paths(path_to_buck_out, True):
        log_files.append(LogFile(id=find_dist_build_run_id(log_path), path=log_path))
    return log_files


def find_local_build_log(path_to_buck_out):
    log_files = []
    for log_path in find_log_paths(path_to_buck_out, False):
        log_files.append(LogFile(id="local", path=log_path))
    if len(log_files) != 1:
        raise Exception("More than 1 local log file found in" + path_to_buck_out)
    return log_files[0]


def find_log_paths(path_to_buck_out, find_dist_build_logs=False):
    log_paths = []
    for rootDir, _, filenames in os.walk(path_to_buck_out):
        # Skip anything which isn't for the type of log we are looking for
        if ("dist-build-slave" in rootDir) != find_dist_build_logs:
            continue
        for filename in filenames:
            if "buck-machine-log" not in filename:
                continue
            log_paths.append(os.path.join(rootDir, filename))
    if len(log_paths) == 0:
        raise Exception(
            "No log files found in: {0}. find_dist_build_logs: {1}".format(
                path_to_buck_out, find_dist_build_logs
            )
        )
    return log_paths


def get_rule_key_of_type(build_rule, rule_key_type):
    if rule_key_type == DEFAULT_RULE_KEY:
        return build_rule.default_rulekey
    elif rule_key_type == INPUT_RULE_KEY:
        return build_rule.input_rulekey
    else:
        raise Exception("{0} is not a supported rule key type".format(rule_key_type))


def diff_rule_keys(build_rule_one, build_rule_two, rule_key_type):
    build_rule_name = build_rule_one.name
    rule_key_one = get_rule_key_of_type(build_rule_one, rule_key_type)
    rule_key_two = get_rule_key_of_type(build_rule_two, rule_key_type)

    if rule_key_one is None or rule_key_two is None:
        return None

    was_match = rule_key_one == rule_key_two
    # print_line_sep("---")
    # print(rule_key_one)
    # print(rule_key_two)
    # print(str(was_match))

    return BuildRuleDiffResult(
        build_rule_name=build_rule_name,
        build_rules=[build_rule_one, build_rule_two],
        rule_key_type=rule_key_type,
        was_match=was_match,
    )


def get_rule_key_type_str(rule_key_type):
    if rule_key_type == DEFAULT_RULE_KEY:
        return "default"
    elif rule_key_type == INPUT_RULE_KEY:
        return "input"
    else:
        raise Exception("{0} is not a supported rule key type".format(rule_key_type))


def print_build_rule_names(build_rule_diff_results):
    for build_rule_diff_result in build_rule_diff_results:
        print(build_rule_diff_result.build_rule_name)


def print_log_diff_result(result):
    print_line_sep("=")
    print(
        """\
Rule key diff result:
Log 1:
- id: {log1.id}
- path: {log1.path}
- extra build rules: {log1_build_rules}
Log 2:
- id: {log2.id}
- path: {log2.path}
- extra build rules: {log2_build_rules}""".format(
            log1=result.log_file_one,
            log1_build_rules=len(
                result.extra_build_rules_by_log_file[result.log_file_one]
            ),
            log2=result.log_file_two,
            log2_build_rules=len(
                result.extra_build_rules_by_log_file[result.log_file_two]
            ),
        )
    )
    for rule_key_type in SUPPORTED_RULE_KEY_TYPES:
        type_str = get_rule_key_type_str(rule_key_type)
        match_results = result.matching_results_by_rule_key_type[rule_key_type]

        print_line_sep("-")
        print("Matching {0} rule keys: {1}".format(type_str, len(match_results)))
        print_line_sep("-")

        mismatch_results = result.mismatching_results_by_rule_key_type[rule_key_type]
        print("Mismatching {0} rule keys: {1}".format(type_str, len(mismatch_results)))
        if len(mismatch_results) > 0:
            print_line_sep("-")
        print_build_rule_names(mismatch_results)


def diff_log_files(log_file_one, log_file_two):
    build_rules_one = read_file(log_file_one)
    build_rules_two = read_file(log_file_two)

    common_rules = set(build_rules_one.keys()).intersection(build_rules_two.keys())

    extra_build_rules_by_log_file = {
        log_file_one: set(build_rules_one.keys()).difference(common_rules),
        log_file_two: set(build_rules_two.keys()).difference(common_rules),
    }

    matching_results_by_rule_key_type = {}
    mismatching_results_by_rule_key_type = {}

    for rule_key_type in SUPPORTED_RULE_KEY_TYPES:
        matching_results_by_rule_key_type[rule_key_type] = []
        mismatching_results_by_rule_key_type[rule_key_type] = []

    for build_rule in sorted(common_rules):
        for rule_key_type in SUPPORTED_RULE_KEY_TYPES:
            build_rule_diff_result = diff_rule_keys(
                build_rules_one[build_rule], build_rules_two[build_rule], rule_key_type
            )
            if build_rule_diff_result is None:
                continue  # No result for this rule key type
            if build_rule_diff_result.was_match:
                matching_results_by_rule_key_type[rule_key_type].append(
                    build_rule_diff_result
                )
            else:
                mismatching_results_by_rule_key_type[rule_key_type].append(
                    build_rule_diff_result
                )

    return LogFileDiffResult(
        log_file_one=log_file_one,
        log_file_two=log_file_two,
        extra_build_rules_by_log_file=extra_build_rules_by_log_file,
        matching_results_by_rule_key_type=matching_results_by_rule_key_type,
        mismatching_results_by_rule_key_type=(mismatching_results_by_rule_key_type),
    )


def compare_local_vs_dist_build(path_to_buck_out_local, path_to_buck_out_dist):
    local_log = find_local_build_log(path_to_buck_out_local)
    dist_logs = find_dist_build_logs(path_to_buck_out_dist)

    diff_results = []
    for dist_log in dist_logs:
        diff_results.append(diff_log_files(local_log, dist_log))

    return diff_results


if __name__ == "__main__":
    args = parseArgs()

    results = compare_local_vs_dist_build(args.localBuildBuckOut, args.distBuildBuckOut)

    for result in results:
        print_log_diff_result(result)
