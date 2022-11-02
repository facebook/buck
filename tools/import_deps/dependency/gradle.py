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
import re
from pathlib import Path

from dependency.pom import MavenDependency


class GradleDependencies:
    """
    Import gradle dependencies.
    """

    def __init__(self) -> None:
        super().__init__()
        self.re_deps_version = re.compile(r"([\d.]+) -> ([\d.]+)(( \([c*]\))|())$")
        self.re_deps_version3 = re.compile(
            r"(\{strictly [\d.]+\}) -> ([\d.]+)(( \([c*]\))|())$"
        )
        self.re_deps_version2 = re.compile(r"([\d.]+)( \([c*]\))|()$")
        self.re_deps_line = re.compile(r"^[+|\\].*$")
        self.re_deps_groups = re.compile(r"^([+|\\][+-\\| ]+)(.*):(.*):(.*)$")

    def import_gradle_dependencies(self, report_file: Path):
        """
        Import gradle dependencies created as
            ./gradlew -q :app:dependencies --configuration debugCompileClasspath > report_file.txt

        Create a set of dependencies.
        The method will pick a resolved version of the dependency.
        I.e. the version that is used by the gradle build.
        :param report_file: a report file path
        :return: a set of dependencies.
        """
        all_dependencies = set()
        dependencies_stack = []
        with open(report_file, "r") as f:
            line = f.readline()
            while line:
                line = f.readline()
                if self.re_deps_line.match(line):
                    m = self.re_deps_groups.match(line)
                    line_prefix = m.group(1)
                    extracted_dependency = self.extract_dependency(line)
                    pd = self.find_dependency(all_dependencies, extracted_dependency)
                    all_dependencies.add(pd)
                    if len(dependencies_stack) == 0:
                        dependencies_stack.append((line_prefix, pd))
                        continue
                    if len(dependencies_stack) > 0:
                        parent = dependencies_stack[len(dependencies_stack) - 1]
                        parent_prefix = parent[0]
                        parent_dep = parent[1]
                        if len(line_prefix) > len(parent_prefix):
                            parent_dep.add_dependency(pd)
                            dependencies_stack.append((line_prefix, pd))
                        elif len(line_prefix) < len(parent_prefix):
                            parent = dependencies_stack.pop()
                            while len(parent[0]) > len(line_prefix):
                                parent = dependencies_stack.pop()
                            if len(dependencies_stack) > 0:
                                parent = dependencies_stack[len(dependencies_stack) - 1]
                                parent_dep = parent[1]
                                parent_dep.add_dependency(pd)
                            dependencies_stack.append((line_prefix, pd))
                        else:
                            dependencies_stack.pop()
                            dependencies_stack.append((line_prefix, pd))
                            if len(dependencies_stack) >= 2:
                                grandparent = dependencies_stack[
                                    len(dependencies_stack) - 2
                                ]
                                grandparent_dep = grandparent[1]
                                grandparent_dep.add_dependency(pd)
        return all_dependencies

    def find_dependency(
        self, all_dependencies: set, pd: MavenDependency
    ) -> MavenDependency:
        for d in all_dependencies:
            if pd.is_keys_equal(d):
                return d
        return pd

    def extract_dependency(self, line: str) -> MavenDependency:
        m = self.re_deps_groups.match(line)
        group_id = m.group(2)
        artifact_id = m.group(3)
        version = self.match_version(m.group(4))
        pd = MavenDependency(group_id, artifact_id, version, "")
        return pd

    def match_version(self, version: str):
        v = version
        vm = self.re_deps_version.match(version)
        if vm:
            v = vm.group(2)
        else:
            vm = self.re_deps_version2.match(version)
            if vm:
                v = vm.group(1)
            else:
                vm = self.re_deps_version3.match(version)
                if vm:
                    v = vm.group(2)
        return v
