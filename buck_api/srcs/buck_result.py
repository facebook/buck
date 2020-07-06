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
from enum import Enum


class ExitCode(Enum):
    """Enum for exit codes of Buck"""

    SUCCESS = 0
    BUILD_ERROR = 1
    BUSY = 2
    COMMANDLINE_ERROR = 3
    NOTHING_TO_DO = 4
    PARSE_ERROR = 5
    RUN_ERROR = 6
    FATAL_GENERIC = 10
    FATAL_BOOTSTRAP = 11
    FATAL_OOM = 12
    FATAL_IO = 13
    FATAL_DISK_FULL = 14
    FIX_FAILED = 16
    TEST_ERROR = 32
    TEST_NOTHING = 64
    SIGNAL_INTERRUPT = 130


class BuckResult:
    """ Represents a Buck process that has finished running """

    def __init__(
        self, process: subprocess.Process, stdout: bytes, stderr: bytes, encoding: str
    ) -> None:
        self.process = process
        self.encoding = encoding
        self.stdout = stdout
        self.stderr = stderr

    def get_exit_code(self) -> ExitCode:
        """ Returns the exit code of a Buck Result when it exits """
        return ExitCode(self.process.returncode)

    def get_stderr(self) -> str:
        """ Returns the standard error of the Buck Result instance """
        return str(self.stderr, self.encoding)

    def get_stdout(self) -> str:
        """ Returns the standard error that is redirected into standard output
            of the Buck Result instance """
        return str(self.stdout, self.encoding)


class BuildResult(BuckResult):
    """ Represents a Buck process  of a build command that has finished running """

    def __init__(
        self, process: subprocess.Process, stdout: bytes, stderr: bytes, encoding: str
    ) -> None:
        super().__init__(process, stdout, stderr, encoding)
