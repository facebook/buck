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
import unittest

from dependency.gradle import GradleDependencies
from dependency.manager import Manager
from dependency.pom import MavenDependency
from tests.test_base import BaseTestCase


class MyTestCase(BaseTestCase):
    def test_import_gradle_dependencies(self):
        file = "data/deps.txt"
        self.assertTrue(os.path.exists(file))
        gd = GradleDependencies()
        deps = gd.import_gradle_dependencies(file)
        self.assertEqual(47, len(deps))
        self.print_dependencies_tree(deps, ">")
        core_runtime: MavenDependency = gd.find_dependency(
            deps, MavenDependency("androidx.arch.core", "core-runtime", "2.1.0")
        )
        self.repo.load_artifacts(core_runtime)
        if len(core_runtime.get_artifacts()) < 1:
            manager = Manager(self.repo)
            manager.import_dep_shallow(core_runtime)
        self.assertEqual(3, len(core_runtime.get_artifacts()))
        core_runtime_deps = core_runtime.get_dependencies()
        self.assertEqual(2, len(core_runtime_deps))
        core_common = gd.find_dependency(
            core_runtime_deps,
            MavenDependency("androidx.arch.core", "core-common", "2.1.0"),
        )
        self.assertEqual(1, len(core_common.get_dependencies()))
        expected_annotation = MavenDependency(
            "androidx.annotation", "annotation", "1.3.0"
        )
        actual_annotation = core_common.get_dependencies().pop()
        self.assertTrue(expected_annotation.is_keys_equal(actual_annotation))

    def print_dependencies_tree(self, dependencies: [], prefix: str = ""):
        for d in dependencies:
            print(f"{prefix} {d}")
            if len(d.get_dependencies()) > 0:
                self.print_dependencies_tree(d.get_dependencies(), f">{prefix}")

    def test_match_version(self):
        gd = GradleDependencies()
        self.assertEqual("1.3.0", gd.match_version("1.3.0"))
        self.assertEqual("1.3.0", gd.match_version("1.3.0 (*)"))
        self.assertEqual("1.3.0", gd.match_version("1.1.0 -> 1.3.0"))
        self.assertEqual("1.3.0", gd.match_version("1.1.0 -> 1.3.0 (*)"))
        self.assertEqual("1", gd.match_version("1"))
        self.assertEqual("1", gd.match_version("1 (*)"))
        self.assertEqual("2", gd.match_version("1 -> 2"))
        self.assertEqual("2", gd.match_version("1 -> 2 (*)"))
        self.assertEqual("2", gd.match_version("1 -> 2 (c)"))
        self.assertEqual("1.5.0", gd.match_version("{strictly 1.5.0} -> 1.5.0 (c)"))

    def test_extract_dependency(self):
        gd = GradleDependencies()
        self.assertEqual(
            MavenDependency("androidx.appcompat", "appcompat", "1.4.1", ""),
            gd.extract_dependency("+--- androidx.appcompat:appcompat:1.4.1"),
        )
        self.assertEqual(
            MavenDependency("androidx.annotation", "annotation", "1.3.0", ""),
            gd.extract_dependency("|    +--- androidx.annotation:annotation:1.3.0"),
        )
        self.assertEqual(
            MavenDependency("androidx.lifecycle", "lifecycle-runtime", "2.4.0", ""),
            gd.extract_dependency(
                "|    |    +--- androidx.lifecycle:lifecycle-runtime:2.3.1 -> 2.4.0 (*)"
            ),
        )
        self.assertEqual(
            MavenDependency("androidx.lifecycle", "lifecycle-viewmodel", "2.3.1", ""),
            gd.extract_dependency(
                "|    |         \\--- androidx.lifecycle:lifecycle-viewmodel:2.3.1 (*)"
            ),
        )
        self.assertEqual(
            MavenDependency("androidx.lifecycle", "lifecycle-livedata", "2.0.0", ""),
            gd.extract_dependency(
                "|    |    |    +--- androidx.lifecycle:lifecycle-livedata:2.0.0"
            ),
        )
        self.assertEqual(
            MavenDependency("javax.inject", "javax.inject", "1", ""),
            gd.extract_dependency("|    |    \\--- javax.inject:javax.inject:1"),
        )
        self.assertEqual(
            MavenDependency("com.google.dagger", "dagger", "2.41", ""),
            gd.extract_dependency("|    |    +--- com.google.dagger:dagger:2.41 (*)"),
        )
        self.assertEqual(
            MavenDependency("com.google.android.material", "material", "1.5.0", ""),
            gd.extract_dependency(
                "+--- com.google.android.material:material:{strictly 1.5.0} -> 1.5.0 (c)"
            ),
        )


if __name__ == "__main__":
    unittest.main()
