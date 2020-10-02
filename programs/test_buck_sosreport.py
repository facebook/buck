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
import shutil
import unittest

from programs.buck_project import BuckProject
from programs.buck_sosreport import SOSReport


@unittest.skipIf(os.name == "nt", "Only works on posix")
class TestSOSReport(unittest.TestCase):
    def test_init(self):
        project = BuckProject.from_current_dir()
        sosreport = SOSReport()
        self.assertIsNotNone(sosreport.absroot)
        self.assertEqual(sosreport.absroot, project.root)
        self.assertEqual(sosreport.buckd_logdir, project.get_buck_out_log_dir())

    def test_get_buckd_pids(self):
        sosreport = SOSReport()
        self.assertTrue(sosreport._get_buckd_pids())

    def test_get_jstack_once(self):
        sosreport = SOSReport()
        self.assertIsNotNone(sosreport.absroot)
        os.makedirs(sosreport.sos_outdir, exist_ok=True)
        pids = sosreport._get_buckd_pids()
        sosreport._get_jstack_once(pids)
        lnewfile = [x for x in os.listdir(sosreport.sos_outdir) if "_jstack_" in x]
        for name in lnewfile:
            newfile = os.path.join(sosreport.sos_outdir, name)
            self.assertTrue(os.path.isfile(newfile))
        shutil.rmtree(sosreport.sos_outdir)


if __name__ == "__main__":
    unittest.main()
