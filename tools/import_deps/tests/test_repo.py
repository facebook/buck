# Copyright (c) Meta Platforms, Inc. and its affiliates.
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
import time
import unittest

from dependency.pom import MavenDependency
from dependency.repo import Repo, ArtifactFlavor
from tests.test_base import BaseTestCase


class TestRepo(BaseTestCase):
    def test_mkdirs(self):
        dirs = ["one", "two", "three"]
        self.assertTrue(self.repo.mkdirs(dirs))
        timeout = time.time() + 5
        while time.time() < timeout:
            if os.path.exists("../libs/one/two/three"):
                break
            time.sleep(1)
        self.assertTrue(os.path.exists(f"{self.repo.root}/one"))
        self.assertTrue(os.path.exists(f"{self.repo.root}/one/two"))
        self.assertTrue(os.path.exists(f"{self.repo.root}/one/two/three"))
        os.rmdir(f"{self.repo.root}/one/two/three")
        os.rmdir(f"{self.repo.root}/one/two")
        os.rmdir(f"{self.repo.root}/one")

    def test_get_dependency_dir(self):
        pom = MavenDependency("com.google.dagger", "dagger", "2.41", "")
        path = self.repo.get_dependency_dir(pom)
        self.assertEqual(
            f"{self.repo.get_libs_name()}/com/google/dagger/dagger/2.41", path.__str__()
        )

    def test_get_dependency_path(self):
        pom = MavenDependency("com.google.dagger", "dagger", "2.41", "")
        path = self.repo.get_dependency_path(pom, ArtifactFlavor.JAR)
        self.assertEqual(
            f"{self.repo.get_libs_name()}/com/google/dagger/dagger/2.41/dagger-2.41.jar",
            path.__str__(),
        )

    def test_get_base_url(self):
        pom = MavenDependency("com.google.dagger", "dagger", "2.41", "")
        url = self.repo.get_base_url(pom)
        self.assertEqual(
            "https://repo1.maven.org/maven2/com/google/dagger/dagger/2.41/dagger-2.41",
            url,
        )

    def test_get_url(self):
        pom = MavenDependency("com.google.dagger", "dagger", "2.41", "")
        url = self.repo.get_url(pom, ArtifactFlavor.JAR)
        self.assertEqual(
            "https://repo1.maven.org/maven2/com/google/dagger/dagger/2.41/dagger-2.41.jar",
            url,
        )
        url = self.repo.get_url(pom, ArtifactFlavor.POM)
        self.assertEqual(
            "https://repo1.maven.org/maven2/com/google/dagger/dagger/2.41/dagger-2.41.pom",
            url,
        )

    def test_download_maven_dep(self):
        pom = MavenDependency("com.google.dagger", "dagger", "2.41", "")
        self.repo.download_maven_dep(pom, ArtifactFlavor.JAR)
        self.assertTrue(
            os.path.exists(
                f"{self.repo.get_libs_name()}/com/google/dagger/dagger/2.41/dagger-2.41.jar"
            )
        )
        file = self.repo.download_maven_dep(pom, ArtifactFlavor.POM)
        self.assertTrue(os.path.exists(file))
        self.assertEqual(
            f"{self.repo.get_libs_name()}/com/google/dagger/dagger/2.41/dagger-2.41.pom",
            file,
        )
        self.repo.download_maven_dep(pom, ArtifactFlavor.AAR)
        self.assertFalse(
            os.path.exists(
                f"{self.repo.get_libs_name()}/com/google/dagger/dagger/2.41/dagger-2.41.aar"
            )
        )

    def test_get_release_version(self):
        dep = MavenDependency("com.google.dagger", "dagger", "2.41", "")
        result = self.repo.get_release_version(dep)
        self.assertTrue(
            os.path.exists(
                f"{self.repo.get_libs_name()}/com/google/dagger/dagger/maven-metadata.xml"
            )
        )
        self.assertEqual("2.42", result)


if __name__ == "__main__":
    unittest.main()
