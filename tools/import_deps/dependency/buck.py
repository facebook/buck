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
import io
import logging
import os
import re
from pathlib import Path

from dependency.pom import MavenDependency
from dependency.repo import Repo, ArtifactFlavor


class Buck:
    BUCK_FILE = "BUCK"

    def __init__(self, repo: Repo) -> None:
        super().__init__()
        self.repo = repo

    def create(self, buck_path: Path, dependency: MavenDependency, visited: set):
        """
        Creates or updates a BUCK file for a single dependency.
        :param buck_path: a path to BUCK file.
        :param dependency: a dependency.
        :param visited: a set of visited dependencies
        :return:
        """
        assert dependency is not None
        if visited.__contains__(dependency):
            return

        mode = "a+"

        with buck_path.open(mode) as f:
            f.write(self.generate_prebuilt_jar_or_aar_rule(dependency))
            f.write("\n")
            f.write(self.android_library(dependency))

        visited.add(dependency)

    def get_all_dependencies(self, pom: MavenDependency, visited=set()) -> []:
        if visited.__contains__(pom):
            return visited
        visited.add(pom)
        for d in pom.dependsOn:
            self.get_all_dependencies(d, visited)
        return visited

    def get_path(self) -> Path:
        p = Path(os.path.join(self.repo.get_root(), Buck.BUCK_FILE))
        return p

    def get_buck_path_for_dependency(self, dependency: MavenDependency) -> Path:
        artifact_base_dir = Path(self.repo.root)
        for d in dependency.group_id.split("."):
            artifact_base_dir = os.path.join(artifact_base_dir, d)
        p = Path(os.path.join(artifact_base_dir, Buck.BUCK_FILE))
        return p

    def fix_path(self, file: Path):
        f = str(file)
        f = f.replace("..", "/")
        return f

    def generate_prebuilt_jar_or_aar_rule(self, pom: MavenDependency) -> str:
        """
        Generate android_prebuilt_aar or prebuilt_jar rules for an
        artifact.
        :param pom: a dependency.
        :return:
        """
        artifact = pom.get_artifact()
        if artifact is None:
            logging.debug(f"pom has no artifacts: {pom}")
            return ""
        # assert artifact is not None, f"pom has no artifacts: {pom}"
        template = None
        if re.match(r".*.aar$", artifact):
            template = self.get_android_prebuilt_aar(pom)
        elif re.match(r".*.jar$", artifact):
            template = self.get_prebuilt_jar(pom)
        return template

    def android_library(self, pom: MavenDependency):
        artifact = pom.get_artifact()
        artifact_path = self.fix_path(artifact)
        header = f"""
android_library(
  name = "{pom.artifact_id}",
"""
        footer = f"""  visibility = [ "PUBLIC" ],
)
"""
        with io.StringIO() as out:
            out.write(header)
            out.write(f"""  exported_deps = [ \n    ":_{pom.artifact_id}", \n""")
            dependencies = pom.get_dependencies()
            for d in dependencies:
                out.write(f'    "{self.repo.get_buck_target(d)}",\n')
            out.write(f"""  ],\n""")
            out.write(footer)
            contents = out.getvalue()
            return contents

    def get_android_prebuilt_aar(self, dep: MavenDependency):
        artifact_name = self.repo.get_artifact_name(dep, ArtifactFlavor.AAR)
        artifact_path = f"{dep.artifact_id}/{dep.version}/{artifact_name}"
        with io.StringIO() as out:
            out.write(
                f"""
android_prebuilt_aar(
  name = '_{dep.artifact_id}',
  aar = '{artifact_path}',
"""
            )
            out.write(")\n")
            contents = out.getvalue()
            return contents

    def get_prebuilt_jar(self, dep: MavenDependency):
        artifact = dep.get_artifact()
        artifact_name = self.repo.get_artifact_name(dep, ArtifactFlavor.JAR)
        artifact_path = f"{dep.artifact_id}/{dep.version}/{artifact_name}"
        with io.StringIO() as out:
            out.write(
                f"""
prebuilt_jar(
  name = '_{dep.artifact_id}',
  binary_jar = '{artifact_path}',
"""
            )
            out.write(")\n")
            contents = out.getvalue()
            return contents
