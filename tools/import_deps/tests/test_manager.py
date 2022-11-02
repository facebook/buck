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
import logging
import os
import unittest

from dependency.buck import Buck
from dependency.gradle import GradleDependencies
from dependency.manager import Manager
from dependency.pom import MavenDependency, PomXmlAdapter
from dependency.repo import ArtifactFlavor
from tests.test_base import BaseTestCase


class TestManager(BaseTestCase):
    def test_import_maven_dep(self):
        gradle_dep = "com.google.dagger:hilt-android:2.41"
        manager = Manager(self.repo)
        pd = manager.import_maven_dep(gradle_dep)
        self.assertEqual(
            MavenDependency("com.google.dagger", "hilt-android", "2.41", ""), pd
        )
        self.assertFalse(
            os.path.exists(
                f"{self.repo.root}/com/google/dagger/hilt-android/2.41/hilt-android-2.41.jar"
            )
        )
        self.assertTrue(
            os.path.exists(
                f"{self.repo.root}/com/google/dagger/hilt-android/2.41/hilt-android-2.41.pom"
            )
        )
        self.assertTrue(
            os.path.exists(
                f"{self.repo.root}/com/google/dagger/hilt-android/2.41/hilt-android-2.41.aar"
            )
        )

    def test_toPomDependency(self):
        gradle_dep = "com.google.dagger:hilt-android:2.41"
        manager = Manager(self.repo)
        pd = manager.to_maven_dependency(gradle_dep)
        self.assertEqual(
            MavenDependency("com.google.dagger", "hilt-android", "2.41", ""), pd
        )

    def test_import_dep_shallow(self):
        self.assertTrue(os.path.exists(self.deps_file))
        gd = GradleDependencies()
        deps = gd.import_gradle_dependencies(self.deps_file)
        self.assertEqual(47, len(deps))
        manager = Manager(self.repo)
        for d in deps:
            downloaded_files = manager.import_dep_shallow(d)
            self.assertTrue(len(downloaded_files) > 0, f"Unable to download: {d}")

    def test_import_dep(self):
        gradle_dep = "com.google.dagger:hilt-android:2.41"
        manager = Manager(self.repo)
        pd = manager.to_maven_dependency(gradle_dep)
        all_dependencies = manager.import_dep(pd)
        for n in all_dependencies:
            print(n)
        self.assertEqual(52, len(all_dependencies))

    def test_check_missing_dependencies_partial_list(self):
        manager = Manager(self.repo)
        pom = MavenDependency("androidx.viewpager", "viewpager", "1.0.0", "")
        manager.import_dep_shallow(pom)
        core = MavenDependency("androidx.core", "core", "1.0.0", "compile")
        pom.add_dependency(core)
        missing_deps = manager.check_missing_dependencies(pom)
        self.assertEqual(2, len(missing_deps))

    def test_check_missing_dependencies_empty_list(self):
        manager = Manager(self.repo)
        dependency = MavenDependency(
            "androidx.concurrent", "concurrent-futures", "1.0.0", "runtime"
        )
        manager.import_dep_shallow(dependency)
        missing_deps = manager.check_missing_dependencies(dependency)
        logging.debug("missing dependencies:")
        dependency.print_dependency_tree()
        for d in missing_deps:
            logging.debug(d)
        self.assertEqual(2, len(missing_deps))
        annotation = MavenDependency(
            "androidx.annotation", "annotation", "1.1.0", "compile"
        )
        listenablefuture = MavenDependency(
            "com.google.guava", "listenablefuture", "1.0", "compile"
        )
        self.assertEqual({annotation, listenablefuture}, missing_deps)

    def test_import_missing_dependencies_single_dep(self):
        manager = Manager(self.repo)
        dependency = MavenDependency("androidx.viewpager", "viewpager", "1.0.0", "")
        manager.import_dep_shallow(dependency)
        versions = dict()
        annotations = MavenDependency(
            "androidx.annotation", "annotation", "1.3.0", "compile"
        )
        versions[annotations.get_group_and_artifactid()] = annotations
        manager.import_missing_dependencies({dependency}, versions)
        self.assertEqual(3, len(dependency.get_dependencies()))
        self.assertTrue(annotations in dependency.get_dependencies())
        all_dependencies = dependency.get_all_dependencies()
        self.assertEqual(9, len(all_dependencies))
        print("\n\n*** all dependencies")
        for d in all_dependencies:
            print(d)
        print("\n\n*** dependencies tree")
        dependency.print_dependency_tree()
        print("\n\n*** versions")
        for k, v in versions.items():
            print(f"{k}:{v}")
        self.assertEqual(9, len(versions))

    def test_import_missing_dependencies_multiple_deps(self):
        manager = Manager(self.repo)
        dependency = MavenDependency(
            "androidx.concurrent", "concurrent-futures", "1.0.0", "runtime"
        )
        manager.import_dep_shallow(dependency)
        versions = dict()
        annotations = MavenDependency(
            "androidx.annotation", "annotation", "1.3.0", "compile"
        )
        versions[annotations.get_group_and_artifactid()] = annotations
        manager.import_missing_dependencies({dependency, annotations}, versions)
        self.assertEqual(2, len(dependency.get_dependencies()))
        self.assertTrue(annotations in dependency.get_dependencies())
        all_dependencies = dependency.get_all_dependencies()
        self.assertEqual(3, len(all_dependencies))
        print("\n\n*** all dependencies")
        for d in all_dependencies:
            print(d)
        print("\n\n*** dependencies tree")
        dependency.print_dependency_tree()
        print("\n\n*** versions")
        for k, v in versions.items():
            print(f"{k}:{v}")
        self.assertEqual(3, len(versions))

    def test_import_missing_dependencies_updates_versions(self):
        manager = Manager(self.repo)
        dependency = MavenDependency("androidx.appcompat", "appcompat", "1.4.1", "")
        manager.import_dep_shallow(dependency)
        versions = dict()
        annotations = MavenDependency(
            "androidx.annotation", "annotation", "1.3.0", "compile"
        )
        versions[annotations.get_group_and_artifactid()] = annotations
        manager.import_missing_dependencies({dependency, annotations}, versions)
        self.assertEqual(14, len(dependency.get_dependencies()))
        self.assertTrue(annotations in dependency.get_dependencies())
        all_dependencies = dependency.get_all_dependencies()
        print("\n\n*** all dependencies")
        keys = set()
        for d in all_dependencies:
            print(d)
            keys.add(d.get_group_and_artifactid())
        print("\n\n*** dependencies tree")
        dependency.print_dependency_tree()
        self.assertEqual(34, len(all_dependencies))
        print("\n\n*** versions")
        for k, v in versions.items():
            print(f"{k}:{v}")
        self.assertEqual(set(versions.keys()), keys)

    def test_import_missing_dependencies_all_gradle_deps(self):
        file = "data/deps_with_missing.txt"
        self.assertTrue(os.path.exists(file))
        gd = GradleDependencies()
        deps = gd.import_gradle_dependencies(file)
        buck = Buck(self.repo)
        # Remove ./libs/BUCK file if it exists
        buck_file = buck.get_path()
        if os.path.exists(buck_file):
            os.remove(buck_file)
        manager = Manager(self.repo)
        versions = dict()
        print("\n\n*** all dependencies")
        for dep in deps:
            manager.import_dep_shallow(dep)
            versions[dep.get_group_and_artifactid()] = dep
            print(dep)
        self.assertEqual(4, len(versions))
        # Check for missing dependencies
        manager.import_missing_dependencies(deps, versions)
        self.assertTrue("androidx.annotation_annotation-experimental" in versions)
        self.assertEqual(
            "androidx.annotation:annotation-experimental:1.1.0:compile",
            versions["androidx.annotation_annotation-experimental"].__str__(),
        )
        # self.assertEqual("androidx.emoji2:emoji2:1.0.0:runtime",
        #                  versions["androidx.emoji2_emoji2"].__str__())
        print("\n\n*** versions")
        for k, v in versions.items():
            print(f"{k}:{v}")
        packages = set()
        print("\n\n*** dependencies tree")
        appcompat = versions["androidx.appcompat_appcompat"]
        appcompat.print_dependency_tree()
        for dep in deps:
            packages.add(dep)
            dep_tree = dep.get_all_dependencies()
            packages = packages.union(dep_tree)
            # dep.print_dependency_tree()
            # print("\n")
        unique_package_keys = set()
        for p in packages:
            unique_package_keys.add(p.get_group_and_artifactid())
        self.assertEqual(set(versions.keys()), unique_package_keys)
        # find dup packages
        for k in unique_package_keys:
            dups = []
            for p in packages:
                if p.get_group_and_artifactid() == k:
                    dups.append(p)
            if len(dups) > 1:
                print(f"dups: {dups}")
        self.assertEqual(len(versions), len(packages))
        self.assertEqual(34, len(versions))

    def test_import_missing_dependencies_resove_missing_versions(self):
        dependency = MavenDependency("com.google.guava", "guava", "31.0.1-jre", "")
        versions = dict()
        manager = Manager(self.repo)
        manager.import_dep_shallow(dependency)
        jar_file = (
            f"{self.repo.root}/com/google/guava/guava/31.0.1-jre/guava-31.0.1-jre.jar"
        )
        self.assertTrue(os.path.exists(jar_file))
        visited = manager.import_missing_dependencies({dependency}, versions)
        keys = set()
        for d in visited:
            keys.add(d.__str__())
        self.assertSetEqual(
            {
                "com.google.guava:guava:31.0.1-jre:",
                "com.google.guava:failureaccess:1.0.1:",
                "com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava:",
                "com.google.code.findbugs:jsr305:3.0.2:",
                "org.checkerframework:checker-qual:3.22.0:",
                "com.google.errorprone:error_prone_annotations:2.13.1:",
                "com.google.j2objc:j2objc-annotations:1.3:",
            },
            keys,
        )

    def test_resolve_deps_versions(self):
        dependency = MavenDependency("com.google.guava", "guava", "31.0.1-jre", "")
        manager = Manager(self.repo)
        manager.import_dep_shallow(dependency)
        pom_file = self.repo.download_maven_dep(dependency, ArtifactFlavor.POM)
        self.assertTrue(os.path.exists(pom_file))
        pom_xml_adapter = PomXmlAdapter(pom_file)
        pom_deps = pom_xml_adapter.get_deps()
        manager.resolve_deps_versions(dependency, pom_deps)
        keys = set()
        for d in pom_deps:
            keys.add(d.__str__())
        self.assertSetEqual(
            {
                "com.google.guava:failureaccess:1.0.1:",
                "com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava:",
                "com.google.code.findbugs:jsr305:3.0.2:",
                "org.checkerframework:checker-qual:3.22.0:",
                "com.google.errorprone:error_prone_annotations:2.13.1:",
                "com.google.j2objc:j2objc-annotations:1.3:",
            },
            keys,
        )


if __name__ == "__main__":
    unittest.main()
