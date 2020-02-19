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
import tempfile
import unittest

from programs.buck_tool import BuckToolException
from programs.java_lookup import _get_java_path_for_highest_minor_version, get_java_path
from programs.subprocutils import which


ANY_JAVA_VERSION = 8
JAVA_VERSION_THAT_OBVIOUSLY_CANT_EXIST_LOCALLY = 500


class TestJavaPath(unittest.TestCase):
    def setUp(self):
        self.java_home = tempfile.mkdtemp()
        self.java_exec = "java.exe" if os.name == "nt" else "java"
        bin_dir = os.path.join(self.java_home, "bin")
        os.mkdir(bin_dir)
        open(os.path.join(bin_dir, self.java_exec), "w")

    def test_with_java_home_valid(self):
        os.environ["JAVA_HOME"] = self.java_home
        self.assertEqual(
            get_java_path(ANY_JAVA_VERSION).lower(),
            os.path.join(self.java_home, "bin", self.java_exec).lower(),
        )

    def test_with_java_home_invalid(self):
        os.environ["JAVA_HOME"] = "/nosuchfolder/89aabebc-42cb-4cd8-bcf7-d964371daf3e"
        self.assertRaises(BuckToolException)

    def test_without_java_home(self):
        self.assertEquals(
            get_java_path(JAVA_VERSION_THAT_OBVIOUSLY_CANT_EXIST_LOCALLY).lower(),
            which("java").lower(),
        )

    def test_java_home_for_wrong_version_ignored(self):
        os.environ["JAVA_HOME"] = (
            "/Library/Java/JavaVirtualMachines/jdk-"
            + str(JAVA_VERSION_THAT_OBVIOUSLY_CANT_EXIST_LOCALLY + 1)
            + ".jdk/Contents/Home"
        )
        self.assertEquals(
            get_java_path(JAVA_VERSION_THAT_OBVIOUSLY_CANT_EXIST_LOCALLY).lower(),
            which("java").lower(),
        )

    def test_java_home_for_wrong_version_not_ignored_if_respect_java_home_set(self):
        os.environ["JAVA_HOME"] = (
            "/Library/Java/JavaVirtualMachines/jdk-"
            + str(JAVA_VERSION_THAT_OBVIOUSLY_CANT_EXIST_LOCALLY + 1)
            + ".jdk/Contents/Home"
        )
        os.environ["BUCK_RESPECT_JAVA_HOME"] = "1"
        self.assertRaises(BuckToolException)

    def test_java_8_highest_version_lookup(self):
        java_base_path = tempfile.mkdtemp()
        os.mkdir(os.path.join(java_base_path, "jdk1.7.0"))
        os.mkdir(os.path.join(java_base_path, "jdk1.8.0_100"))
        os.mkdir(os.path.join(java_base_path, "jdk1.8.0_200"))
        os.mkdir(os.path.join(java_base_path, "jdk1.8.1"))
        os.mkdir(os.path.join(java_base_path, "jdk1.8.1_100"))

        self.assertEquals(
            _get_java_path_for_highest_minor_version(java_base_path, 8),
            os.path.join(java_base_path, "jdk1.8.1_100"),
        )

    def test_openjdk_8_highest_version_lookup(self):
        java_base_path = tempfile.mkdtemp()
        os.mkdir(os.path.join(java_base_path, "adoptopenjdk-7.jdk"))
        os.mkdir(os.path.join(java_base_path, "adoptopenjdk-8.jdk"))
        os.mkdir(os.path.join(java_base_path, "adoptopenjdk-9.jdk"))
        os.mkdir(os.path.join(java_base_path, "adoptopenjdk-18.jdk"))

        self.assertEquals(
            _get_java_path_for_highest_minor_version(java_base_path, 8),
            os.path.join(java_base_path, "adoptopenjdk-8.jdk"),
        )

    def test_java_11_highest_version_lookup(self):
        java_base_path = tempfile.mkdtemp()
        os.mkdir(os.path.join(java_base_path, "jdk-10.0.1"))
        os.mkdir(os.path.join(java_base_path, "jdk-11.0.1"))
        os.mkdir(os.path.join(java_base_path, "jdk-11.0.2"))
        os.mkdir(os.path.join(java_base_path, "jdk-11.0.2_100"))
        os.mkdir(os.path.join(java_base_path, "jdk-11.0.2_200"))
        os.mkdir(os.path.join(java_base_path, "jdk-12"))
        os.mkdir(os.path.join(java_base_path, "jdk-13"))

        self.assertEquals(
            _get_java_path_for_highest_minor_version(java_base_path, 11),
            os.path.join(java_base_path, "jdk-11.0.2_200"),
        )

    def test_openjdk_11_highest_version_lookup(self):
        java_base_path = tempfile.mkdtemp()
        os.mkdir(os.path.join(java_base_path, "adoptopenjdk-7.jdk"))
        os.mkdir(os.path.join(java_base_path, "adoptopenjdk-8.jdk"))
        os.mkdir(os.path.join(java_base_path, "adoptopenjdk-9.jdk"))
        os.mkdir(os.path.join(java_base_path, "adoptopenjdk-10.jdk"))
        os.mkdir(os.path.join(java_base_path, "adoptopenjdk-11.jdk"))
        os.mkdir(os.path.join(java_base_path, "adoptopenjdk-11.0.2.jdk"))
        os.mkdir(os.path.join(java_base_path, "adoptopenjdk-12.jdk"))

        self.assertEquals(
            _get_java_path_for_highest_minor_version(java_base_path, 11),
            os.path.join(java_base_path, "adoptopenjdk-11.0.2.jdk"),
        )
        self.assertEquals(
            _get_java_path_for_highest_minor_version(java_base_path, 12),
            os.path.join(java_base_path, "adoptopenjdk-12.jdk"),
        )

    def tearDown(self):
        if "JAVA_HOME" in os.environ:
            del os.environ["JAVA_HOME"]
        if "BUCK_RESPECT_JAVA_HOME" in os.environ:
            del os.environ["BUCK_RESPECT_JAVA_HOME"]
        shutil.rmtree(self.java_home)


if __name__ == "__main__":
    unittest.main()
