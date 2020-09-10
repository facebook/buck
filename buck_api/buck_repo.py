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

import os
from asyncio import subprocess
from contextlib import contextmanager
from pathlib import Path
from typing import Awaitable, DefaultDict, Dict, Iterator, Optional, Tuple

from buck_api.buck_config import BuckConfig
from buck_api.buck_process import BuckProcess
from buck_api.buck_result import BuckResult, BuildResult, TestResult


class BuckRepo:
    """ Instantiates a BuckRepo object with a exectuable path """

    def __init__(
        self, path_to_buck: Path, encoding: str, cwd: Optional[Path] = None
    ) -> None:
        assert path_to_buck.exists(), str(path_to_buck)
        self.path_to_buck = path_to_buck
        self.cwd = Path() if cwd is None else cwd
        assert self.cwd.exists(), str(self.cwd)
        self.encoding = encoding
        self.set_buckd(False)
        self._buck_config: Optional[BuckConfig] = None
        self._buck_config_local: Optional[BuckConfig] = None
        ######################################
        #  path_to_buck is the absolute path
        ######################################

    @contextmanager
    def buck_config(self) -> Iterator[DefaultDict[str, Dict[str, str]]]:
        """
        A context manager that yields .buckconfig configs as a dictionary
        On close, the configs are saved to .buckconfig file.
        """
        if self._buck_config is None:
            self._buck_config = BuckConfig(self.cwd / Path(".buckconfig"))
        with self._buck_config.modify() as config:
            yield config

    @contextmanager
    def buck_config_local(self) -> Iterator[DefaultDict[str, Dict[str, str]]]:
        """
        A context manager that yields .buckconfig.local configs as a dictionary
        On close, the configs are saved to .buckconfig.local file.
        """
        if self._buck_config_local is None:
            self._buck_config_local = BuckConfig(self.cwd / Path(".buckconfig.local"))
        with self._buck_config_local.modify() as config:
            yield config

    def set_buckd(self, toggle: bool) -> None:
        """
        Setting buckd env to value of toggle.
        toggle can be 0 for enabled and 1 for disabled
        """
        child_environment = dict(os.environ)
        child_environment["NO_BUCKD"] = str(int(toggle))
        self.buckd_env = child_environment

    def build(self, *argv: str) -> BuckProcess[BuildResult]:
        """
        Returns a BuckProcess with BuildResult type using a process
        created with the build command and any
        additional arguments
        """
        awaitable_process = self._run_buck_command("build", *argv)
        return BuckProcess(
            awaitable_process,
            result_type=lambda proc, stdin, stdout, encoding: BuildResult(
                proc, stdin, stdout, encoding, str(self.cwd), *argv
            ),
            encoding=self.encoding,
        )

    def clean(self, *argv: str) -> BuckProcess[BuckResult]:
        """
        Returns a BuckProcess with BuckResult type using a process
        created with the clean command and any
        additional arguments
        """
        awaitable_process = self._run_buck_command("clean", *argv)
        return BuckProcess(
            awaitable_process, result_type=BuckResult, encoding=self.encoding
        )

    def kill(self) -> BuckProcess[BuckResult]:
        """
        Returns a BuckProcess with BuckResult type using a process
        created with the kill command
        """
        awaitable_process = self._run_buck_command("kill")
        return BuckProcess(
            awaitable_process, result_type=BuckResult, encoding=self.encoding
        )

    def test(self, *argv: str) -> BuckProcess[TestResult]:
        """
        Returns a BuckProcess with TestResult type using a process
        created with the test command and any
        additional arguments
        """
        xml_flag, test_output_file = self._create_xml_file()
        awaitable_process = self._run_buck_command("test", *argv, xml_flag)
        return BuckProcess(
            awaitable_process,
            result_type=lambda proc, stdin, stdout, encoding: TestResult(
                proc, stdin, stdout, encoding, str(self.cwd / test_output_file)
            ),
            encoding=self.encoding,
        )

    def _run_buck_command(self, cmd: str, *argv: str) -> Awaitable[subprocess.Process]:
        """
        Returns a process created from the execuable path,
        command and any additional arguments
        """
        awaitable_process = subprocess.create_subprocess_exec(
            str(self.path_to_buck),
            cmd,
            cwd=self.cwd,
            env=self.buckd_env,
            *argv,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        return awaitable_process

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
