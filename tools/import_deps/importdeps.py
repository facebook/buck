#!/usr/bin/env python3
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

import argparse
import logging
import os

from dependency.buck import Buck
from dependency.gradle import GradleDependencies
from dependency.manager import Manager
from dependency.repo import Repo


def main():
    parser = argparse.ArgumentParser(
        description="""Import gradle dependencies and generate BUCK file.
        Created gradle dependencies file by running 
        ./gradlew -q :app:dependencies --configuration debugCompileClasspath > report_file.txt
        """
    )

    parser.add_argument("--gdeps", required=True, help="gradle dependencies file")
    parser.add_argument(
        "--libs", default="third-party", help="libs folder for imported dependencies"
    )
    try:
        args = parser.parse_args()
        print(f"args: {args}")
        gdeps = args.gdeps
        libs = args.libs
        print(f"gdeps: {gdeps}, libs: {libs}")
        import_deps(gdeps, libs)
    except argparse.ArgumentError:
        print("Invalid arguments")


def import_deps(gradle_deps: str, libs: str) -> None:
    """
    Import dependencies and create BUCK file.
    :param gradle_deps: file with gradle dependencies
    :param libs: path to a folder where to store the dependencies
    :return: None
    """
    logging.basicConfig(level=logging.INFO)
    assert os.path.exists(gradle_deps), f"Unable to open {gradle_deps}"
    if not os.path.exists(libs):
        os.mkdir(libs)
    assert os.path.exists(libs)
    gd = GradleDependencies()
    # Importing dependencies from a file, will not determine the scope.
    # I.e. whether it is a runtime or compile time dependency.
    # We will need to parse pom for the dependency and update it.
    deps = gd.import_gradle_dependencies(gradle_deps)
    repo = Repo(libs=libs)
    buck = Buck(repo)
    # Remove ./libs/BUCK file if it exists
    buck_file = buck.get_path()
    if os.path.exists(buck_file):
        os.remove(buck_file)
    manager = Manager(repo)

    versions = dict()
    for dep in deps:
        manager.import_dep_shallow(dep)
        versions[dep.get_group_and_artifactid()] = dep

    # Check for missing dependencies
    manager.import_missing_dependencies(deps, versions)

    visited = set()
    for dep in versions.values():
        buck_file = buck.get_buck_path_for_dependency(dep)
        # This will also update a BUCK file
        print(f"adding {dep}")
        buck.create(buck_file, dep, visited)
        visited.add(dep)


main()
