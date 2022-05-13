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
import re
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import List, Optional


class Artifact:
    """
    A specific file of a dependency: jar, aar, pom, etc.
    """

    def __init__(self, file: Path):
        self.file = file

    def _key(self):
        return self.file

    def __hash__(self) -> int:
        return hash(self._key())

    def __eq__(self, o: object) -> bool:
        if not isinstance(o, Artifact):
            return False
        return o.file == self.file


class MavenDependency:
    """
    A Maven dependency that contains artifacts (jar, aar, ...) and has its
    own dependencies.
    """

    def __init__(
        self, group_id: str, artifact_id: str, version: str, scope: str = "runtime"
    ) -> None:
        self.group_id = group_id
        self.artifact_id = artifact_id
        self.version = version
        self.scope = scope
        self.dependsOn = set()
        self.artifacts = set()

    def __str__(self) -> str:
        return f"{self.group_id}:{self.artifact_id}:{self.version}:{self.scope}"

    def __hash__(self) -> int:
        return hash(self._key())

    def add_dependency(self, dependency: "MavenDependency") -> None:
        """
        Adds a dependency. The dependency will be added if
        it has a different group and artifactId than self,
        or it is new to the collection of dependencies,
        or if its version is larger than the version of an existing
        dependency. In this case, the dependency is substituted.
        :param dependency: a dependency to be added.
        """
        if dependency == self:
            return
        if self.get_group_and_artifactid() == dependency.get_group_and_artifactid():
            return
        if self.dependsOn.__contains__(dependency):
            return
        partial_match = self.find_dep(dependency.group_id, dependency.artifact_id)
        if partial_match is not None:
            larger_version = self.get_larger_version(
                partial_match.version, dependency.version
            )
            if partial_match.version is not larger_version:
                self.dependsOn.remove(partial_match)
                self.dependsOn.add(dependency)
                return
            return
        self.dependsOn.add(dependency)

    def get_larger_version(self, v1: str, v2: str) -> str:
        x = [v1, v2]
        x.sort()
        return x[1]

    def add_artifact(self, artifact: Artifact):
        self.artifacts.add(artifact)

    def get_artifact(self):
        for artifact in self.artifacts:
            file = artifact.file
            if (
                re.match(r".*.aar$", file)
                or re.match(r".*.jar$", file)
                and not re.match(r".*-sources.jar$", file)
            ):
                return file

    def get_artifacts(self):
        return self.artifacts

    def is_keys_equal(self, pd: "MavenDependency"):
        return (
            pd.artifact_id == self.artifact_id
            and pd.group_id == self.group_id
            and pd.version == self.version
        )

    def is_group_and_artifactid_equal(self, pd: "MavenDependency"):
        return pd.artifact_id == self.artifact_id and pd.group_id == self.group_id

    def get_group_and_artifactid(self):
        return f"{self.group_id}_{self.artifact_id}"

    def get_version(self):
        return self.version

    def update_version(self, version: str):
        self.version = version

    def get_dependencies(self) -> set:
        return self.dependsOn

    def get_all_dependencies(self) -> set:
        """
        Find all dependencies recursively.
        :return: a set of dependencies.
        """
        visited = set()
        queue = set()
        queue.add(self)
        while len(queue) > 0:
            d = queue.pop()
            if d in visited:
                continue
            visited.add(d)
            for n in d.get_dependencies():
                queue.add(n)
        return visited

    def print_dependency_tree(self):
        visited = set()
        prefix = ""
        self._print_dependency_tree(prefix, visited)

    def _print_dependency_tree(self, prefix: str, visited: set):
        if self in visited:
            if len(self.get_dependencies()) > 0:
                print(f"{prefix} {self.__str__()} ...")
            else:
                print(f"{prefix} {self.__str__()}")
            return
        visited.add(self)
        print(f"{prefix} {self.__str__()}")
        prefix = f"{prefix} >"
        for d in self.get_dependencies():
            d._print_dependency_tree(prefix, visited)

    def get_compile_dependencies(self) -> set:
        compile_dependencies = set()
        for d in self.dependsOn:
            if d.scope == "compile":
                compile_dependencies.add(d)
        return compile_dependencies

    def get_runtime_dependencies(self) -> set:
        runtime_dependencies = set()
        for d in self.dependsOn:
            if d.scope != "compile":
                runtime_dependencies.add(d)
        return runtime_dependencies

    def _key(self):
        return self.group_id, self.artifact_id, self.version, self.scope

    def get_libs_path(self):
        l = self.group_id.split(".")
        l.append(self.artifact_id)
        l.append(self.version)
        return l

    def get_artifact_versions_dir(self) -> []:
        """
        Provides a master folder for the artifact. This is a base for sub-folders with specific artifact versions.
        :return: a folder path.
        """
        l = self.group_id.split(".")
        l.append(self.artifact_id)
        return l

    def __eq__(self, o: object) -> bool:
        if not isinstance(o, MavenDependency):
            return False
        return (
            self.group_id == o.group_id
            and self.artifact_id == o.artifact_id
            and self.version == o.version
            and self.scope == o.scope
        )

    def get_full_name(self):
        return f"{self.group_id}:{self.artifact_id}"

    def get_group_id(self):
        return self.group_id

    def get_artifact_id(self):
        return self.artifact_id

    def get_scope(self):
        return self.scope

    def set_scope(self, scope: str):
        self.scope = scope

    def find_dep(self, group_id: str, artifact_id: str):
        for d in self.dependsOn:
            if d.get_full_name() == f"{group_id}:{artifact_id}":
                return d


class PomXmlAdapter:
    _NAMESPACES = {"xmlns": "http://maven.apache.org/POM/4.0.0"}

    def __init__(self, pom_file: str):
        self._pom_file = pom_file

    def get_deps(self) -> List[MavenDependency]:
        deps = []
        if not os.path.exists(self._pom_file):
            logging.warning(f"The pom file is not found: {self._pom_file}")
            return deps
        tree = ET.parse(self._pom_file)
        root = tree.getroot()
        dependencies = root.findall(
            "./xmlns:dependencies/xmlns:dependency", namespaces=self._NAMESPACES
        )
        for dep_node in dependencies:
            dependency = self._create_pom_dependency(dep_node, root)
            deps.append(dependency)
        # TODO: revisit the "test" scope
        deps = [d for d in deps if d.version != "" and d.scope != "test"]
        return deps

    def get_group_id(self, root):
        group_id = root.find("./xmlns:groupId", namespaces=self._NAMESPACES)
        if group_id is None:
            group_id = root.find(
                "./xmlns:parent/xmlns:groupId", namespaces=self._NAMESPACES
            )
        return group_id.text

    def get_package_version(self, root):
        """
        Provides a version of the main artifact of the POM.
        :param root: an XML tree root
        :return: an artifact version
        """
        version = root.find("./xmlns:version", namespaces=self._NAMESPACES)
        if version is None:
            version = root.find(
                "./xmlns:parent/xmlns:version", namespaces=self._NAMESPACES
            )
        if version is None:
            version = root.find("./version")
        if version is None:
            logging.warning(f"Unable to find the version for {self._pom_file}")
            version = root.find("version")
            logging.debug(f"Project version: {version}")
        return version

    def get_dependency_version(self, dependency_node, root):
        """
        Provides a version of a dependency.
        :param dependency_node: a dependency XML node
        :param artifact_version: a version of the artifact
        :param root: an XML tree root
        :return: a dependency version. None if no version is provided.
        """
        version_element = dependency_node.find(
            "xmlns:version", namespaces=self._NAMESPACES
        )
        if version_element is None:
            # Some POMs have dependencies without versions. For example:
            # third-party/com/google/guava/guava-testlib/31.1-jre/guava-testlib-31.1-jre.pom
            return None
        # Sometimes version is specified with a property or a reference to a
        # project (main artifact) version.
        # Check if the version is specified with a variable that starts with ${...
        x = re.compile(r"\${(.*)}")
        r = x.match(version_element.text)
        if r is not None:
            property_name = r.group(1)
            if property_name.startswith("project"):
                version_element = self.get_package_version(root)
            else:
                # <version>${hamcrestVersion}</version>
                version_element = root.find(
                    f"./xmlns:properties/xmlns:{property_name}",
                    namespaces=self._NAMESPACES,
                )
            if version_element is not None:
                logging.warning(f"Unable to find definition of {property_name}")
        version = None
        if version_element is not None:
            version_text = PomXmlAdapter.get_or_empty(version_element)
            version = self.resolve_complicated_version(version_text)
        return version

    def _create_pom_dependency(self, dep_node, root) -> MavenDependency:
        group_id = dep_node.find("xmlns:groupId", namespaces=self._NAMESPACES).text
        if group_id == "${project.groupId}":
            group_id = self.get_group_id(root)
        assert group_id is not None, f"groupId is not found for {self._pom_file}"
        artifact_id = dep_node.find(
            "xmlns:artifactId", namespaces=self._NAMESPACES
        ).text
        version_text = self.get_dependency_version(dep_node, root)
        scope = PomXmlAdapter.get_or_empty(
            dep_node.find("xmlns:scope", namespaces=self._NAMESPACES)
        )
        logging.debug(
            f"groupid: {group_id}, artifactId: {artifact_id}, version: {version_text}"
        )
        pd = MavenDependency(group_id, artifact_id, version_text, scope)
        return pd

    def get_or_empty(e: Optional[ET.Element]) -> str:
        if e is None:
            return ""
        else:
            # pyre-ignore
            return e.text

    def resolve_complicated_version(self, v: str, default_version: str = None) -> str:
        """
        Eventually we want to resolve all uncommon pom.xml version patterns:
         - <version>1.0.1</version> - simple version (Supported)
         - <version>[1.0.1]</version> - exact version (Supported)
         - <version>[1.0.0,2.0.0)</version> - range with upper bound (NOT supported)
         - <version>[1.0.0,)</version> - range without upper bound (NOT supported)
         Will not support:
         - <version>LATEST</version> - removed in Maven 3.0
         - <version>RELEASE</version> - removed in Maven 3.0
         - <version>{VERSION_VARIABLE_FROM_POM}</version> - too complicated
        """
        # (v), [v), or [v]
        if v.isdecimal():
            return v
        if re.match(r"\d+\.\d+\.\d+", v):
            return v
        if re.match(r"\d+\.\d+", v):
            return v
        bounded = re.search(r"^[\[\(].*[\]\)]$", v)
        if bounded:
            return v[1:-1]
        else:
            # default
            return default_version


class MavenMetadataParser:
    """
    This is a maven-metadata.xml parser.
    """

    def get_release_version(self, file: str) -> str:
        """
        Parses maven-metadata.xml and returns a release version.
        :param file: a maven-metadata.xml
        :return: a release version
        """
        assert os.path.exists(file)
        tree = ET.parse(file)
        root = tree.getroot()
        version = root.find("./versioning/release")
        return version.text
