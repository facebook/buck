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

from asyncio import subprocess

from buck_process import BuckProcess
from buck_result import BuildResult


class BuckRepo:
    """ Instiates a BuckRepo object with a exectuable path """

    def __init__(self, path_to_buck: str, encoding: str, cwd: str = None) -> None:
        self.path_to_buck = path_to_buck
        self.cwd = cwd
        self.encoding = encoding
        ######################################
        #  path_to_buck is the absolute path
        ######################################

    async def build(self, *argv: str) -> BuckProcess[BuildResult]:
        """
        Returns a BuckProcess with BuildResult type using a process
        created with the build command and any
        additional arguments
        """
        process = await self._runBuckCommand("build", *argv)
        return BuckProcess(process, result_type=BuildResult, encoding=self.encoding)

    async def _runBuckCommand(self, cmd: str, *argv: str) -> subprocess.Process:
        """
        Returns a process created from the execuable path,
        command and any additional arguments
        """
        process = await subprocess.create_subprocess_exec(
            self.path_to_buck,
            cmd,
            cwd=self.cwd,
            *argv,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE
        )
        return process
