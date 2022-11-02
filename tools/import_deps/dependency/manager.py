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
from collections import deque

from .pom import MavenDependency, PomXmlAdapter, Artifact
from .repo import Repo, ArtifactFlavor


class Manager:
    """
    Provides methods to import Maven dependencies to a local repository (../libs).
    """

    # A stack of pom files to be updated and parsed as we add more dependencies.
    pom_files = []

    def __init__(self, repo: Repo) -> None:
        super().__init__()
        self.repo = repo

    def import_maven_dep(self, gradle_dependency: str):
        """
        Import a maven dependency as well as its dependencies.
        Create BUCK files.
        :param gradle_dependency:
        :return: a PomDependency
        """
        pom_dependency = self.to_maven_dependency(gradle_dependency)
        self.import_dep(pom_dependency)
        return pom_dependency

    def import_dep(self, dependency: MavenDependency) -> set:
        all_dependencies = set()
        visited = set()
        return self._import_dep(dependency, all_dependencies, visited)

    def _import_dep(
        self, dependency: MavenDependency, all_dependencies, visited=set()
    ) -> set:
        """
        Import a maven dependency with its transitive dependencies.
        :param all_dependencies:
        :param dependency:
        :param all_dependencies: set of all dependencies
        :param visited: a set of visited dependencies
        :return:
        """
        if visited.__contains__(dependency.__str__()):
            return all_dependencies
        visited.add(dependency.__str__())
        all_dependencies.add(dependency)

        for flavor in Repo.artifact_flavors:
            file = self.repo.download_maven_dep(dependency, flavor)
            if not os.path.exists(file):
                logging.debug(f"Unable to download: {file}")
            else:
                dependency.add_artifact(Artifact(file))
                if flavor == ArtifactFlavor.POM:
                    self.pom_files.append(file)

        # parse pom
        while len(self.pom_files) > 0:
            pom_file = self.pom_files.pop()
            pom_xml_adapter = PomXmlAdapter(pom_file)
            deps = pom_xml_adapter.get_deps()
            for d in deps:
                dependency.add_dependency(d)
                self._import_dep(d, all_dependencies, visited)

        return all_dependencies

    def import_dep_shallow(self, dependency: MavenDependency) -> []:
        """
        Import a maven dependency artifacts without its transitive dependencies.
        :param dependency:
        :return: a list of downloaded files
        """
        downloaded_files = []
        for flavor in Repo.artifact_flavors:
            file = self.repo.download_maven_dep(dependency, flavor)
            if os.path.exists(file):
                downloaded_files.append(file)
                dependency.add_artifact(Artifact(file))
        return downloaded_files

    def resolve_deps_versions(self, dependency: MavenDependency, deps: []) -> None:
        """
        In some cases POM does not have versions for dependencies.
        This method obtains a release version for such dependencies.
        :param dependency: a dependncy to be updated
        :param deps: dependencies
        :return: None
        """
        for d in deps:
            if d.version is None:
                d.version = self.repo.get_release_version(d)
                dependency.add_dependency(d)

    def import_deps_shallow(self, deps: set):
        """
        Import dependencies from the set.
        Do not import dependsOn dependencies.

        :param deps: a set of dependencies
        :return:
        """
        for d in deps:
            self.import_dep_shallow(d)

    def to_maven_dependency(self, gradle_dependency: str) -> MavenDependency:
        d = gradle_dependency.split(":")
        pd = MavenDependency(d[0], d[1], d[2], "")
        return pd

    def import_missing_dependencies(self, deps: set, versions: dict) -> set:
        visited = set()
        self._import_missing_dependencies(deps, versions, visited)
        return visited

    def _import_missing_dependencies(self, deps: set, versions: dict, visited: set):
        queue = deque(deps)
        while len(queue) > 0:
            dep: MavenDependency = queue.popleft()
            if dep in visited:
                continue
            visited.add(dep)
            dep = self.get_larger_version(dep, versions)
            missing_dependencies = self.check_missing_dependencies(dep)
            for md in missing_dependencies:
                if md.version is None:
                    md.version = self.repo.get_release_version(md)
                md = self.get_larger_version(md, versions)
                assert md.version is not None
                dep.add_dependency(md)
                self.import_dep_shallow(md)
                queue.append(md)

    def get_larger_version(
        self, dep: MavenDependency, versions: dict
    ) -> MavenDependency:
        """
        Find a matching version in the versions dict. Return it if it is a larger version.
        Or return the dep.
        :param dep:
        :param versions: a repository of objects with the larger version
        :return:
        """
        key = dep.get_group_and_artifactid()
        if key not in versions.keys():
            versions[key] = dep
            return dep
        larger_version = dep.get_larger_version(versions[key].version, dep.version)
        if dep != larger_version:
            return versions[key]
        return dep

    def check_missing_dependencies(self, dep: MavenDependency) -> set:
        pom_file = self.repo.download_maven_dep(dep, ArtifactFlavor.POM)
        pom_xml_adapter = PomXmlAdapter(pom_file)
        pom_deps = pom_xml_adapter.get_deps()
        missing = set(pom_deps) - dep.get_dependencies()
        return missing

    def update_versions(
        self, dep: MavenDependency, versions: dict, visited=set()
    ) -> None:
        """
        Build a set of all dependencies with the largest version numbers./
        :param dep:
        :param versions:
        :param visited:
        :param all_dependencies:
        :return:
        """
        if visited.__contains__(dep):
            return
        visited.add(dep)

        # if the dep has a lover version than in versions, then return also
        group_and_artifactid = dep.get_group_and_artifactid()
        if group_and_artifactid in versions.keys():
            v = versions[group_and_artifactid]
            largest_version = dep.get_larger_version(dep.version, v)
            if largest_version is v:
                return

        # at this point dep either unique or has a large version
        # set or bump the version up
        versions[group_and_artifactid] = dep.version

        pom_file = self.repo.download_maven_dep(dep, ArtifactFlavor.POM)
        pom_xml_adapter = PomXmlAdapter(pom_file)
        pom_deps = pom_xml_adapter.get_deps()

        for d in pom_deps:
            self.get_all_dependencies(d, versions, visited)

    def update_dependencies(
        self, dep: MavenDependency, versions: dict, visited=set()
    ) -> None:
        if visited.__contains__(dep):
            return
        visited.add(dep)

        pom_file = self.repo.download_maven_dep(dep, ArtifactFlavor.POM)
        pom_xml_adapter = PomXmlAdapter(pom_file)
        pom_deps = pom_xml_adapter.get_deps()

        deps = dep.get_dependencies()

        for d in pom_deps:
            if d not in deps:
                v = versions[d.get_group_and_artifactid()]
                x = d
                if v is not d.version:
                    x = MavenDependency(d.group_id, d.artifact_id, v)
                    self.import_dep_shallow(x)
                dep.add_dependency(x)
                self.update_dependencies(d, versions, visited)
