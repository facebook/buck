# Copyright 2016-present Facebook, Inc.
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

import glob
import os
import unittest

from project_workspace import ProjectWorkspace


class LogRotationTest(unittest.TestCase):
    def test_log_retention(self):
        """ Tests that the default java.util.logging setup can maintain at least 'a couple'
            of log files. """
        test_data = os.path.join(
            "test", "com", "facebook", "buck", "log", "testdata", "log"
        )

        with ProjectWorkspace(test_data) as workspace:
            iterations = 3
            for i in xrange(0, iterations):
                returncode = workspace.run_buck(
                    "targets",
                    "//:foo",
                )
                self.assertEquals(0, returncode)

            logs = glob.glob(
                os.path.join(
                    workspace.test_data_directory, "buck-out", "log", "buck*.log"
                )
            )
            self.assertEquals(iterations, len(logs))


if __name__ == "__main__":
    unittest.main()
