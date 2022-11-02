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
from enum import Enum
from pathlib import Path
from typing import List

import urllib3

from .pom import Artifact, PomXmlAdapter, MavenMetadataParser
from .pom import MavenDependency


class ArtifactFlavor(Enum):
    JAR = ".jar"
    AAR = ".aar"
    POM = ".pom"
    SOURCE_JAR = "-sources.jar"


class Repo:
    repo_url_maven = "https://repo1.maven.org/maven2/"
    repo_url_google = "https://maven.google.com/"
    artifact_flavors: List[ArtifactFlavor] = [
        ArtifactFlavor.JAR,
        ArtifactFlavor.AAR,
        ArtifactFlavor.POM,
        ArtifactFlavor.SOURCE_JAR,
    ]

    def __init__(self, libs="libs") -> None:
        super().__init__()
        self.libs = libs
        self.root = f"./{libs}"

    def get_libs_name(self):
        """
        :return: a name of the repo libs folder
        """
        return self.libs

    def get_base_name(self) -> str:
        return f"//{os.path.basename(self.libs)}"

    def get_buck_target(self, dep: MavenDependency) -> str:
        """
        Provides a fully qualified name for the dependency library in the repo.
        :param dep: a dependency
        :return: a fully qualified name for the dependency library in the repo.
        """
        artifact_base_dir = f"{self.get_base_name()}"
        for p in dep.group_id.split("."):
            artifact_base_dir = artifact_base_dir + "/" + p
        target = artifact_base_dir + ":" + dep.artifact_id
        return target

    def get_root(self):
        return self.root

    def mkdirs(self, path: list) -> bool:
        """
        Create directories in libs folder.
        """
        if not os.path.exists(self.root) or not os.path.isdir(self.root):
            return False

        d = self.get_path(path)
        if not os.path.isdir(d):
            os.makedirs(name=d, exist_ok=True)
        return os.path.isdir(d)

    def get_path(self, path: list) -> Path:
        d = self.root
        for folder in path:
            d = os.path.join(d, folder)
        return d

    def get_dependency_dir(self, dep: MavenDependency) -> Path:
        d = Path(self.root)
        for folder in dep.get_libs_path():
            d = os.path.join(d, folder)
        return d

    def get_dependency_path(self, dep: MavenDependency, flavor: ArtifactFlavor) -> Path:
        """
        Provides a path to an artifact file in the local repo.
        """
        d = self.get_dependency_dir(dep)
        file_name = self.get_artifact_name(dep, flavor)
        path = os.path.join(d, file_name)
        return path

    def get_artifact_name(self, dep: MavenDependency, flavor: ArtifactFlavor):
        file_name = f"{dep.artifact_id}-{dep.version}{flavor.value}"
        return file_name

    def get_base_url(self, dep: MavenDependency):
        group = dep.group_id.replace(".", "/")
        maven_repo = self.get_maven_repo(dep)
        base_url: str = f"{maven_repo}{group}/{dep.artifact_id}/{dep.version}/{dep.artifact_id}-{dep.version}"
        return base_url

    def get_maven_repo(self, dep: MavenDependency):
        if dep.group_id.startswith("androidx") or dep.group_id.startswith(
            "com.google.android"
        ):
            return self.repo_url_google
        return self.repo_url_maven

    def get_release_version(self, dep: MavenDependency) -> str:
        """
        Check Maven repos and obtain a release version of a dependency.

        :param dep:
        :return: a version from maven-metadata.xml.
        """
        maven_repo = self.get_maven_repo(dep)
        group = dep.group_id.replace(".", "/")
        maven_metadata_url: str = (
            f"{maven_repo}{group}/{dep.artifact_id}/maven-metadata.xml"
        )
        artifact_base_dir = Path(self.root)
        for d in dep.get_artifact_versions_dir():
            artifact_base_dir = os.path.join(artifact_base_dir, d)
        if not os.path.exists(artifact_base_dir):
            os.makedirs(artifact_base_dir, exist_ok=True)
        maven_metadata_file = os.path.join(artifact_base_dir, "maven-metadata.xml")
        downloaded = self.download(maven_metadata_url, maven_metadata_file)
        if not downloaded:
            return None
        parser = MavenMetadataParser()
        version = parser.get_release_version(maven_metadata_file)
        return version

    def get_url(self, dep: MavenDependency, flavor: ArtifactFlavor):
        return f"{self.get_base_url(dep)}{flavor.value}"

    def download(self, url, path) -> bool:
        with urllib3.PoolManager() as http:
            r = http.request("GET", url, preload_content=False)
            if r.status != 200:
                return False
            with open(path, "wb") as out:
                while True:
                    data = r.read()
                    if not data:
                        break
                    out.write(data)
            r.release_conn()
        return True

    def download_maven_dep(
        self, dep: MavenDependency, flavor: ArtifactFlavor, force: bool = False
    ) -> Path:
        """
        Download a Maven dependency
        :param dep: the base dependency
        :param flavor: a flavor: jar, aar, pom, etc
        :param force: download even if the file exists
        :return: a dependency file path
        """
        destination = self.get_dependency_dir(dep)
        if not os.path.exists(destination):
            os.makedirs(destination, exist_ok=True)
        url = self.get_url(dep, flavor)
        repo_file = self.get_dependency_path(dep, flavor)
        if not os.path.exists(repo_file) or force:
            self.download(url, repo_file)
            logging.debug(f"url: {url}, file: {os.path.abspath(repo_file)}")
        else:
            logging.debug(f"url: {url}, file: {repo_file} exists")
        return repo_file

    def load_artifacts(self, pom: MavenDependency):
        """
        Check the disk for available artifacts and add them to the
        collection.
        :return:
        """
        destination = self.get_dependency_dir(pom)
        if not os.path.exists(destination):
            return

        for flavor in Repo.artifact_flavors:
            repo_file = self.get_dependency_path(pom, flavor)
            if os.path.exists(repo_file):
                pom.add_artifact(Artifact(repo_file))

    def update_scope_from_pom(self, dep: MavenDependency):
        pom_file = self.download_maven_dep(dep, ArtifactFlavor.POM)
        pom_xml_adapter = PomXmlAdapter(pom_file)
        pom_deps = pom_xml_adapter.get_deps()
        for pd in pom_deps:
            d = dep.find_dep(pd.get_group_id(), pd.get_artifact_id())
            if d is None:
                logging.debug(f"dependency not found: {pd} in {dep}")
                return
            if d.get_scope() != pd.get_scope():
                d.set_scope(pd.get_scope())
