#!/usr/bin/env python3
# Copyright (c) Facebook, Inc. and its affiliates.
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


from __future__ import print_function

import os
import subprocess
import sys

from programs.buck_package import BuckPackage
from programs.buck_project import BuckProject
from programs.buck_tool import BuckStatusReporter
from programs.java_lookup import get_java_path
from programs.tracing import Tracing


class LogdPackage(BuckPackage):
    """
    LogdPackage is a BuckPackage specifically tailored for the execution of LogD.
    Its responsibility is to start and stop LogD,
    using Buck's bootstrap class loader to do so.
    """

    def __init__(self, buck_project, buck_reporter):
        super(LogdPackage, self).__init__(buck_project, buck_reporter)

    def environ_for_buck(self):
        """
        Add appropriate classpath to buck environment
        :return: a dict containing environment properties
        """
        env = os.environ.copy()

        classpath = str(self._get_bootstrap_classpath())

        # On Java 9 and higher, the BuckFileSystem jar gets loaded
        # via the bootclasspath and doesn't need to be added here.
        if self.get_buck_compiled_java_version() < 9:
            classpath += os.pathsep + str(self._get_buckfilesystem_classpath())

        env["CLASSPATH"] = classpath
        env["BUCK_CLASSPATH"] = str(self._get_java_classpath())

        return env


def main(argv, reporter):
    def get_repo(project):
        """
        Returns LogD package
        :param project: BuckProject init from cwd
        :return: LogD package
        """
        return LogdPackage(project, reporter)

    def launch_logd(java_path, env):
        """
        Launches LogD server
        :param java_path: path to java jdk
        :param env: buck environment properties
        :return: the returncode attribute
        """
        command = ["buck"]
        extra_default_options = [
            "-Dfile.encoding=UTF-8",
            "-XX:SoftRefLRUPolicyMSPerMB=0",
            "-XX:+UseG1GC",
        ]

        command.extend(extra_default_options)
        command.append("com.facebook.buck.cli.bootstrapper.ClassLoaderBootstrapper")
        command.append("com.facebook.buck.logd.server.LogdServerMain")

        with Tracing("logd", args={"command": command}):
            return subprocess.call(
                command,
                cwd=BuckProject.from_current_dir().root,
                env=env,
                executable=java_path,
            )

    with BuckProject.from_current_dir() as project:
        with get_repo(project) as logd_repo:
            required_java_version = logd_repo.get_buck_compiled_java_version()
            java_path = get_java_path(required_java_version)
            env = logd_repo.environ_for_buck()

            return launch_logd(java_path, env)


if __name__ == "__main__":
    reporter = BuckStatusReporter(sys.argv)
    main(sys.argv, reporter)
