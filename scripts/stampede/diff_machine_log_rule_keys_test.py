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
import unittest

import diff_machine_log_rule_keys as rule_key_differ
from diff_machine_log_rule_keys import DEFAULT_RULE_KEY, INPUT_RULE_KEY


def get_test_dir(test_dir):
    this_dir = os.path.dirname(os.path.realpath(__file__))
    return os.path.join(this_dir, "testdata", test_dir)


class TestRuleKeyDiffer(unittest.TestCase):
    def test_finds_dist_logs(self):
        dist_build_logs = rule_key_differ.find_dist_build_logs(
            get_test_dir("machine_logs")
        )

        self.assertEqual(2, len(dist_build_logs))

        # Ensure the dist build run IDs were extracted correctly
        run_1_matches = filter(lambda x: x.id == "run-1", dist_build_logs)
        self.assertEqual(1, len(run_1_matches))

        run_2_matches = filter(lambda x: x.id == "run-2", dist_build_logs)
        self.assertEqual(1, len(run_2_matches))

    def test_finds_local_logs(self):
        raised = False
        try:
            local_log = rule_key_differ.find_local_build_log(
                get_test_dir("machine_logs")
            )
            self.assertEqual("local", local_log.id)
        except:
            raised = True
        self.assertFalse(raised, "Exception raised")

    def test_finds_matches_and_mismatches(self):
        machine_logs_dir = get_test_dir("machine_logs")
        results = rule_key_differ.compare_local_vs_dist_build(
            machine_logs_dir, machine_logs_dir
        )

        ##############################
        # Log file contents:
        ##############################
        # Local:
        # //build/rule:one:
        #   default: HASH1
        #   input: null
        # //build/rule:two:
        #   default: HASH2
        #   input: INPUTHASH1
        #
        # Dist build run 1:
        # //build/rule:one:
        #   default: HASH1
        #   input: null
        # //build/rule:two:
        #   default: HASH2
        #   input: INPUTHASH1
        # //build/rule:extraOne ..
        # //build/rule:extraTwo ..
        #
        # Dist build run 2:
        # //build/rule:two:
        #   default: HASH2
        #   input: INPUTHASH3

        ###############################
        # Local vs dist build run 1
        ###############################

        local_vs_dist_1 = filter(
            lambda x: x.log_file_one.id == "local" and x.log_file_two.id == "run-1",
            results,
        )
        self.assertEqual(1, len(local_vs_dist_1))
        local_vs_dist_1 = local_vs_dist_1[0]

        extra_rules_dist_1 = local_vs_dist_1.extra_build_rules_by_log_file[
            local_vs_dist_1.log_file_two
        ]
        extra_rules_dist_1 = sorted(list(extra_rules_dist_1))
        self.assertEqual(2, len(extra_rules_dist_1))
        self.assertEqual("//build/rule:extraOne", extra_rules_dist_1[0])
        self.assertEqual("//build/rule:extraTwo", extra_rules_dist_1[1])

        default_mismatches_1 = local_vs_dist_1.mismatching_results_by_rule_key_type[
            DEFAULT_RULE_KEY
        ]
        input_mismatches_1 = local_vs_dist_1.mismatching_results_by_rule_key_type[
            INPUT_RULE_KEY
        ]
        self.assertEqual(0, len(default_mismatches_1))
        self.assertEqual(0, len(input_mismatches_1))

        default_matches_1 = local_vs_dist_1.matching_results_by_rule_key_type[
            DEFAULT_RULE_KEY
        ]
        input_matches_1 = local_vs_dist_1.matching_results_by_rule_key_type[
            INPUT_RULE_KEY
        ]
        self.assertEqual(2, len(default_matches_1))
        self.assertEqual(default_matches_1[0].build_rule_name, "//build/rule:one")
        self.assertEqual(default_matches_1[1].build_rule_name, "//build/rule:two")
        self.assertEqual(1, len(input_matches_1))

        ###############################
        # Local vs dist build run 2
        ###############################

        local_vs_dist_2 = filter(
            lambda x: x.log_file_one.id == "local" and x.log_file_two.id == "run-2",
            results,
        )
        self.assertEqual(1, len(local_vs_dist_2))
        local_vs_dist_2 = local_vs_dist_2[0]

        extra_rules_local_2 = local_vs_dist_2.extra_build_rules_by_log_file[
            local_vs_dist_2.log_file_one
        ]
        extra_rules_local_2 = sorted(list(extra_rules_local_2))
        self.assertEqual(1, len(extra_rules_local_2))
        self.assertEqual("//build/rule:one", extra_rules_local_2[0])

        default_mismatches_2 = local_vs_dist_2.mismatching_results_by_rule_key_type[
            DEFAULT_RULE_KEY
        ]
        input_mismatches_2 = local_vs_dist_2.mismatching_results_by_rule_key_type[
            INPUT_RULE_KEY
        ]
        self.assertEqual(0, len(default_mismatches_2))
        self.assertEqual(1, len(input_mismatches_2))
        self.assertEqual("//build/rule:two", input_mismatches_2[0].build_rule_name)

        default_matches_2 = local_vs_dist_2.matching_results_by_rule_key_type[
            DEFAULT_RULE_KEY
        ]
        input_matches_2 = local_vs_dist_2.matching_results_by_rule_key_type[
            INPUT_RULE_KEY
        ]
        self.assertEqual(1, len(default_matches_2))
        self.assertEqual(default_matches_2[0].build_rule_name, "//build/rule:two")
        self.assertEqual(0, len(input_matches_2))


if __name__ == "__main__":
    unittest.main()
