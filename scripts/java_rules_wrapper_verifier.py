# Copyright (c) Meta Platforms, Inc. and affiliates.
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


class JavaRulesWrapperTest(unittest.TestCase):
    def test_java_rules(self):
        self.verify_wrapper_labels("java_library", "wrapped_with_buck_java_rules")
        self.verify_wrapper_labels("java_binary", "wrapped_with_buck_java_rules")
        self.verify_wrapper_labels("prebuilt_jar", "wrapped_with_buck_java_rules")

    def verify_wrapper_labels(self, rule, wrapper_label):
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
                "kind({}, deps(//...))".format(rule),
                "--output-attributes",
                "labels",
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
            "buck query didn't return any results for rule = {}".format(rule),
        )

        broken_targets = []
        for target_name in entries:
            attributes = entries[target_name]
            labels = attributes.get("labels", None)
            if (labels is not None) and ("lint_ignore" in labels):
                continue

            if (labels is None) or (wrapper_label not in labels):
                broken_targets.append(target_name)

        self.assertEqual(
            len(broken_targets),
            0,
            "{} of type {} don't contain {}".format(
                broken_targets, rule, wrapper_label
            ),
        )


if __name__ == "__main__":
    unittest.main()
