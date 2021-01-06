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

from __future__ import absolute_import, division, print_function, unicode_literals

import json
import os
import platform
import subprocess
import unittest


def findRepoRoot(cwd):
    while os.path.exists(cwd):
        if os.path.exists(os.path.join(cwd, ".buckconfig")):
            return cwd
        cwd = os.path.dirname(cwd)
    raise Exception("Could not locate buck repo root.")


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
        repo_root = findRepoRoot(os.getcwd())

        env = dict(os.environ.items())
        env["NO_BUCKD"] = "1"
        if platform.system() == "Windows":
            buck_path = os.path.join(repo_root, "bin", "buck.bat")
            cmd_prefix = ["cmd.exe", "/C", buck_path]
        else:
            buck_path = os.path.join(repo_root, "bin", "buck")
            cmd_prefix = [buck_path]
        proc = subprocess.Popen(
            cmd_prefix
            + [
                "query",
                "kind(java_library, deps({}))".format(targets_to_check),
                "--output-attributes",
                "source",
                "target",
            ],
            stdout=subprocess.PIPE,
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


if __name__ == "__main__":
    unittest.main()
