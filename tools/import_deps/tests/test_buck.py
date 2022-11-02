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
import filecmp
import os
import unittest
from pathlib import Path

from dependency.buck import Buck
from dependency.gradle import GradleDependencies
from dependency.manager import Manager
from dependency.pom import MavenDependency
from tests.test_base import BaseTestCase


class BuckTestCase(BaseTestCase):
    def test_create(self):
        buck = Buck(self.repo)
        dependency = MavenDependency("com.google.dagger", "dagger", "2.41", "")
        buck_file = buck.get_buck_path_for_dependency(dependency)
        manager = Manager(self.repo)
        downloaded_files = manager.import_dep_shallow(dependency)
        buck.create(buck_file, dependency, set())
        self.assertTrue(os.path.exists(buck_file))
        self.assertTrue(
            filecmp.cmp("data/buck/buck1.txt", buck_file),
            f"files are different: {buck_file}",
        )

    def test_create_with_some_dependencies(self):
        buck = Buck(self.repo)
        dependency = MavenDependency("com.google.dagger", "dagger", "2.41", "")
        buck_file = buck.get_buck_path_for_dependency(dependency)
        manager = Manager(self.repo)
        downloaded_files = manager.import_dep(dependency)
        self.assertEqual(3, len(dependency.get_artifacts()))
        self.assertEqual(1, len(dependency.get_dependencies()))
        buck.create(buck_file, dependency, set())
        self.assertTrue(os.path.exists(buck_file))
        self.assertTrue(
            filecmp.cmp("data/buck/buck2.txt", buck_file),
            "dagger BUCK files are different",
        )

    def test_create_with_aar_dependencies(self):
        buck = Buck(self.repo)
        dependency = MavenDependency("com.google.dagger", "hilt-android", "2.41", "")
        buck_file = buck.get_buck_path_for_dependency(dependency)
        expected_file = os.path.join(Path(self.repo.root), "com/google/dagger/BUCK")
        self.assertEqual(expected_file, buck_file.__str__())
        manager = Manager(self.repo)
        downloaded_files = manager.import_dep(dependency)
        self.assertEqual(3, len(dependency.get_artifacts()))
        self.assertEqual(13, len(dependency.get_dependencies()))
        buck.create(buck_file, dependency, set())
        self.assertTrue(os.path.exists(expected_file))

    def test_create_with_all_dependencies(self):
        """
        Read all dependencies from the gradle.
        Create BUCK for all these dependencies.
        """
        file = self.deps_file
        self.assertTrue(os.path.exists(file))
        gd = GradleDependencies()
        # Importing dependencies from a file, will not determine the scope.
        # I.e. whether it is runtime or a compile time dependency.
        # We will need to parse pom for the dependency and update it.
        deps = gd.import_gradle_dependencies(file)
        self.assertEqual(47, len(deps))
        buck = Buck(self.repo)
        # Remove ./libs/BUCK file if it exists
        buck_file = buck.get_path()
        if os.path.exists(buck_file):
            os.remove(buck_file)
        manager = Manager(self.repo)
        versions = dict()
        for dep in deps:
            manager.import_dep_shallow(dep)
            versions[dep.get_group_and_artifactid()] = dep

        # Check for missing dependencies
        manager.import_missing_dependencies(deps, versions)

        visited = set()
        for dep in deps:
            buck_file = buck.get_buck_path_for_dependency(dep)
            # This will also update a BUCK file
            buck.create(buck_file, dep, visited)
            visited.add(dep)

    def test_get_path_for_dependency(self):
        buck = Buck(self.repo)
        dependency = MavenDependency(
            "com.google.guava", "guava-testlib", "31.1-jre", ""
        )
        path = buck.get_buck_path_for_dependency(dependency)
        self.assertEqual(Path("../third-party/com/google/guava/BUCK"), path)

    def tearDown(self) -> None:
        super().tearDown()
        buck1 = os.path.join(self.repo.root, "com/google/dagger/BUCK")
        if os.path.exists(buck1):
            os.rename(buck1, os.path.join(self.repo.root, "com/google/dagger/BUCK.bak"))
        buck2 = os.path.join(self.repo.root, "javax/inject/BUCK")
        if os.path.exists(buck2):
            os.rename(buck2, os.path.join(self.repo.root, "javax/inject/BUCK.bak"))


if __name__ == "__main__":
    unittest.main()
