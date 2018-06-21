# Copyright 2017-present Facebook, Inc.
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
import sys
import unittest

from project_workspace import ProjectWorkspace, run_buck_process


class TestBuckRun(unittest.TestCase):
    @unittest.skipUnless(os.name == "posix", "This test fails on windows")
    def test_buck_run(self):
        test_data = os.path.join(
            "test", "com", "facebook", "buck", "cli", "testdata", "buck_run"
        )
        with ProjectWorkspace(test_data) as workspace:
            self.assertEqual(0, workspace.run_buck("run", "//:hello-java"))
            self.assertEqual(0, workspace.run_buck("run", "//:hello-cxx"))
            self.assertEqual(0, workspace.run_buck("run", "//:hello-python"))
            subdir = workspace.resolve_path("subdir")
            os.mkdir(subdir)
            proc = run_buck_process(["run", "//:pwd"], subdir)
            stdout, stderr = proc.communicate()
            sys.stdout.write(stdout)
            sys.stdout.flush()
            sys.stderr.write(stderr)
            sys.stderr.flush()
            self.assertEqual(0, proc.returncode)


if __name__ == "__main__":
    unittest.main()
