# Copyright 2019-present Facebook, Inc.
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
import json
import os
import unittest

from project_workspace import run_buck_process


class JavaVersionTest(unittest.TestCase):
    def test_testrunner_transitive_deps_should_target_java8(self):
        self.verify_java_version_for_transitive_deps(
            "//src/com/facebook/buck/testrunner/...", 8
        )

    def test_jvm_runner_transitive_deps_should_target_java8(self):
        self.verify_java_version_for_transitive_deps(
            "//src/com/facebook/buck/jvm/java/runner:runner", 8
        )

    def test_ideabuck_buck_lib_transitive_deps_should_target_java8(self):
        self.verify_java_version_for_transitive_deps(
            "//src/com/facebook/buck/event/external:external_lib", 8
        )

    def verify_java_version_for_transitive_deps(
        self, targets_to_check, expected_java_version
    ):
        proc = run_buck_process(
            [
                "query",
                "kind(java_library, deps({}))".format(targets_to_check),
                "--output-attributes",
                "source",
                "target",
            ]
        )
        stdout, stderr = proc.communicate()

        if proc.returncode != 0:
            print(stderr)
            self.assertFalse(
                True, "buck query failed with return code " + str(proc.returncode)
            )

        entries = json.loads(stdout)
        self.assertGreater(
            len(entries),
            0,
            "buck query didn't return any results. Did somebody move {}?".format(
                targets_to_check
            ),
        )

        for target_name in entries:
            attributes = entries[target_name]
            source = attributes.get("source", None)
            target = attributes.get("target", None)
            self.assertEqual(
                str(expected_java_version),
                str(source),
                "{} has a `source` value of {}, expected all transitive deps of {} to explicitly target Java {}".format(
                    target_name, source, targets_to_check, expected_java_version
                ),
            )
            self.assertEqual(
                str(expected_java_version),
                str(target),
                "{} has a `target` value of {}, expected all transitive deps of {} to explicitly target Java {}".format(
                    target_name, target, targets_to_check, expected_java_version
                ),
            )
