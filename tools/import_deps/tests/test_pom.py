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

from dependency.pom import MavenDependency, PomXmlAdapter
from dependency.repo import Repo, ArtifactFlavor
from tests.test_base import BaseTestCase


class TestPomDependency(BaseTestCase):
    def test_str(self):
        dependency = MavenDependency("com.google.dagger", "dagger", "2.41", "")
        self.assertEqual("com.google.dagger:dagger:2.41:", dependency.__str__())

    def test_get_libs_path(self):
        dependency = MavenDependency("com.google.dagger", "dagger", "2.41", "")
        path = dependency.get_libs_path()
        self.assertEqual(["com", "google", "dagger", "dagger", "2.41"], path)

    def test_parse_pom(self):
        dependency = MavenDependency("com.google.dagger", "dagger", "2.41", "")
        self.repo.download_maven_dep(dependency, ArtifactFlavor.POM)
        file = self.repo.download_maven_dep(dependency, ArtifactFlavor.POM)
        self.assertTrue(os.path.exists(file))
        self.assertEqual(
            f"{self.repo.get_libs_name()}/com/google/dagger/dagger/2.41/dagger-2.41.pom",
            file,
        )
        pom_xml_adapter = PomXmlAdapter(file)
        deps = pom_xml_adapter.get_deps()
        self.assertEqual(1, len(deps))
        self.assertEqual(
            MavenDependency("javax.inject", "javax.inject", "1", "").__str__(),
            deps[0].__str__(),
        )

    def test_resolve_pom_variables(self):
        """
        Verifies that the method replaces variables in  <dependency>
            <groupId>${project.groupId}</groupId>
            <artifactId>guava</artifactId>
            <version>${project.version}</version>
        with the project groupId and version: "com.google.guava:guava:31.1-jre:".
        """
        dependency = MavenDependency(
            "com.google.guava", "guava-testlib", "31.1-jre", ""
        )
        self.repo.download_maven_dep(dependency, ArtifactFlavor.POM)
        file = self.repo.download_maven_dep(dependency, ArtifactFlavor.POM)
        self.assertTrue(os.path.exists(file))
        self.assertEqual(
            f"{self.repo.get_libs_name()}/com/google/guava/guava-testlib/31.1-jre/guava-testlib-31.1-jre.pom",
            file,
        )
        pom_xml_adapter = PomXmlAdapter(file)
        deps = pom_xml_adapter.get_deps()
        keys = set()
        for d in deps:
            s = d.__str__()
            print(f"dep: {s}")
            keys.add(s)
        self.assertSetEqual(
            {
                "com.google.code.findbugs:jsr305:None:",
                "org.checkerframework:checker-qual:None:",
                "com.google.errorprone:error_prone_annotations:None:",
                "com.google.j2objc:j2objc-annotations:None:",
                "com.google.guava:guava:31.1-jre:",
                "junit:junit:None:compile",
                # "com.google.truth:javax.truth:None:test", - the test is excluded for now
            },
            keys,
        )

    def test_resolve_pom_properties(self):
        dependency = MavenDependency("junit", "junit", "4.13.2", "")
        self.repo.download_maven_dep(dependency, ArtifactFlavor.POM)
        file = self.repo.download_maven_dep(dependency, ArtifactFlavor.POM)
        self.assertTrue(os.path.exists(file))
        self.assertEqual(
            f"{self.repo.get_libs_name()}/junit/junit/4.13.2/junit-4.13.2.pom",
            file,
        )
        pom_xml_adapter = PomXmlAdapter(file)
        deps = pom_xml_adapter.get_deps()
        keys = set()
        for d in deps:
            s = d.__str__()
            print(f"dep: {s}")
            keys.add(s)
        self.assertSetEqual(
            {
                "org.hamcrest:hamcrest-core:1.3:",
                # TODO: check about the test scope
            },
            keys,
        )

    def test_resolve_pom_complex_properties(self):
        dependency = MavenDependency("com.squareup", "javapoet", "1.13.0", "")
        self.repo.download_maven_dep(dependency, ArtifactFlavor.POM)
        file = self.repo.download_maven_dep(dependency, ArtifactFlavor.POM)
        self.assertTrue(os.path.exists(file))
        self.assertEqual(
            f"{self.repo.get_libs_name()}/com/squareup/javapoet/1.13.0/javapoet-1.13.0.pom",
            file,
        )
        pom_xml_adapter = PomXmlAdapter(file)
        deps = pom_xml_adapter.get_deps()
        self.assertEqual(0, len(deps))
        # TODO: update for "test" scope

    def test_get_deps(self):
        dependency = MavenDependency("com.google.dagger", "hilt-core", "2.41", "")
        self.repo.download_maven_dep(dependency, ArtifactFlavor.POM)
        pom_file = f"{self.repo.get_libs_name()}/com/google/dagger/hilt-core/2.41/hilt-core-2.41.pom"
        self.assertTrue(os.path.exists(pom_file))
        pa = PomXmlAdapter(pom_file)
        deps = pa.get_deps()
        self.assertEqual(3, len(deps))
        keys = set()
        for d in deps:
            keys.add(d.__str__())
        self.assertSetEqual(
            {
                "com.google.dagger:dagger:2.41:",
                "com.google.code.findbugs:jsr305:3.0.2:",
                "javax.inject:javax.inject:1:",
            },
            keys,
        )

    def test_add_dependency_version_upgrade(self):
        dependency = MavenDependency("com.google.dagger", "hilt-core", "2.41", "")
        d1 = MavenDependency("androidx.core", "core", "1.7.0", "")
        d2 = MavenDependency("androidx.core", "core", "1.1.0", "")
        d3 = MavenDependency("androidx.core", "core", "1.8.0", "")
        dependency.add_dependency(d1)
        dependency.add_dependency(d2)
        dependency.add_dependency(d3)
        deps = dependency.get_dependencies()
        self.assertEqual(1, len(deps))
        self.assertEqual(deps.pop(), d3)

    def test_add_dependency_excludes_cycles(self):
        dependency = MavenDependency("com.google.dagger", "hilt-core", "2.41", "")
        d1 = MavenDependency("androidx.core", "core", "1.7.0", "")
        d2 = MavenDependency("androidx.core", "core", "1.1.0", "")
        d3 = MavenDependency("com.google.dagger", "hilt-core", "2.41", "")
        dependency.add_dependency(d1)
        dependency.add_dependency(d2)
        dependency.add_dependency(d3)
        deps = dependency.get_dependencies()
        self.assertEqual(1, len(deps))
        self.assertEqual(deps.pop(), d1)


if __name__ == "__main__":
    unittest.main()
