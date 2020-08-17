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
from pathlib import Path
from typing import Tuple

from buck_process import BuckProcess
from buck_result import BuckResult, BuildResult, TestResult


class BuckRepo:
    """ Instantiates a BuckRepo object with a exectuable path """

    def __init__(self, path_to_buck: str, encoding: str, cwd: str) -> None:
        # TODO change cwd to take Path object
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
        process = await self._run_buck_command("build", *argv)
        return BuckProcess(
            process,
            result_type=lambda proc, stdin, stdout, encoding: BuildResult(
                proc, stdin, stdout, encoding, self.cwd, *argv
            ),
            encoding=self.encoding,
        )

    async def clean(self, *argv: str) -> BuckProcess[BuckResult]:
        """
        Returns a BuckProcess with BuckResult type using a process
        created with the clean command and any
        additional arguments
        """
        process = await self._run_buck_command("clean", *argv)
        return BuckProcess(process, result_type=BuckResult, encoding=self.encoding)

    async def kill(self) -> BuckProcess[BuckResult]:
        """
        Returns a BuckProcess with BuckResult type using a process
        created with the kill command
        """
        process = await self._run_buck_command("kill")
        return BuckProcess(process, result_type=BuckResult, encoding=self.encoding)

    async def test(self, *argv: str) -> BuckProcess[TestResult]:
        """
        Returns a BuckProcess with TestResult type using a process
        created with the test command and any
        additional arguments
        """
        xml_flag, test_output_file = self._create_xml_file()
        process = await self._run_buck_command("test", *argv, xml_flag)
        return BuckProcess(
            process,
            result_type=lambda proc, stdin, stdout, encoding: TestResult(
                proc, stdin, stdout, encoding, str(Path(self.cwd) / test_output_file)
            ),
            encoding=self.encoding,
        )

    async def _run_buck_command(self, cmd: str, *argv: str) -> subprocess.Process:
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

    def _create_xml_file(self, *argv: str) -> Tuple[str, str]:
        """
        Creates a xml file used for the test output. Ensures an xml file
        is created if not specified.
        """
        xml_flag = ""
        test_output_file = "testOutput.xml"
        # ensures xml file is always generated
        if "--xml" not in argv:
            xml_flag = "--xml testOutput.xml"
        else:
            test_output_file = argv[argv.index("--xml") + 1]
        return xml_flag, test_output_file
