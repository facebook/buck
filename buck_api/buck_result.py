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

import xml.etree.ElementTree as ET
from asyncio import subprocess
from enum import Enum, auto
from pathlib import Path
from typing import Dict, List


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


class AutoName(Enum):
    """Makes the value of the Enum its name"""

    @staticmethod
    def _generate_next_value_(name, start, count, last_values):
        return name


class ResultType(AutoName):
    """Enum for result types of buck test"""

    DRY_RUN = auto()
    EXCLUDED = auto()
    DISABLED = auto()
    ASSUMPTION_VIOLATION = auto()
    FAILURE = auto()
    SUCCESS = auto()


class BuckResult:
    """ Represents a Buck process that has finished running """

    def __init__(
        self,
        process: subprocess.Process,
        stdout: bytes,
        stderr: bytes,
        encoding: str,
        buck_build_id: str,
    ) -> None:
        self.process = process
        self.encoding = encoding
        self.stdout = stdout
        self.stderr = stderr
        self.buck_build_id = buck_build_id

    def is_success(self) -> bool:
        """ Returns if a Buck Result is successful"""
        return self.get_exit_code() == ExitCode.SUCCESS

    def is_failure(self) -> bool:
        """Returns if a Buck Result fails for any reason"""
        return self.get_exit_code() != ExitCode.SUCCESS

    def get_exit_code(self) -> ExitCode:
        """ Returns the exit code of a Buck Result when it exits """
        # See https://docs.python.org/3/library/subprocess.html#subprocess.Popen.returncode
        # for negative return code.
        if self.process.returncode < 0:
            return ExitCode(128 - self.process.returncode)
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
        self,
        process: subprocess.Process,
        stdout: bytes,
        stderr: bytes,
        encoding: str,
        buck_build_id: str,
        *argv: str,
    ) -> None:
        self.args = argv[0]
        super().__init__(process, stdout, stderr, encoding, buck_build_id)

    def is_build_failure(self) -> bool:
        """Returns if a Build Result fails because of a build failue only"""
        return self.get_exit_code() == ExitCode.BUILD_ERROR

    def get_target_to_build_output(self) -> Dict[str, str]:
        """
        Returns a dict of the build target and file created in buck-out
        Prints to build target followed by path to buck-out file to stdout
        """
        target_to_output = {}
        assert (
            "--show-output" in self.args
        ), "Must add --show-output arg to get build output"
        show_output = self.get_stdout().splitlines()
        for line in show_output:
            output_mapping = line.split()
            assert len(output_mapping) <= 2, "Output mapping should be less than 2"
            target = output_mapping[0]
            if len(output_mapping) == 1:
                target_to_output[target] = ""
            else:
                target_to_output[target] = output_mapping[1]
        return target_to_output


class TestResultSummary:
    """Represents a summary of a test result"""

    def __init__(self, name: str, status: str, result_type: ResultType) -> None:
        self.name: str = name
        self.status: str = status
        self.result_type: ResultType = ResultType(result_type)

    def get_name(self) -> str:
        """Returns the name of the test"""
        return self.name

    def get_status(self) -> str:
        """Returns the status of the test"""
        return self.status

    def get_result_type(self) -> ResultType:
        """Returns the result type of the test"""
        return self.result_type


class TestResult(BuckResult):
    """ Represents a Buck process  of a test command that has finished running """

    def __init__(
        self,
        process: subprocess.Process,
        stdout: bytes,
        stderr: bytes,
        encoding: str,
        buck_build_id: str,
        test_output_file: Path,
    ) -> None:
        super().__init__(process, stdout, stderr, encoding, buck_build_id)
        self.test_root = (
            ET.parse(str(test_output_file)).getroot()
            if test_output_file.exists()
            else None
        )

    def is_test_failure(self) -> bool:
        """Returns if a Test Result fails because of a test failure only"""
        return self.get_exit_code() == ExitCode.TEST_ERROR

    def is_build_failure(self) -> bool:
        """Returns if a Test Result fails because of a build failure only"""
        return self.get_exit_code() == ExitCode.BUILD_ERROR

    def get_tests(self) -> List[TestResultSummary]:
        """Returns a list of test result summaries"""
        if not self.test_root:
            return []
        test_list = []
        for tests in self.test_root:
            for testresult in tests.iter("testresult"):
                name = testresult.get("name")
                status = testresult.get("status")
                testresult_type = testresult.get("type")
                assert testresult_type in (
                    e.value for e in ResultType
                ), f"Type {testresult_type} is not a ResultType Enum"
                result_type = ResultType(testresult_type)
                test_result_summary = TestResultSummary(name, status, result_type)
                test_list.append(test_result_summary)
        return test_list

    def get_success_count(self) -> int:
        """Returns the number of successful tests"""
        return self._get_count(ResultType.SUCCESS)

    def get_failure_count(self) -> int:
        """Returns the number of failed tests"""
        return self._get_count(ResultType.FAILURE)

    def get_skipped_count(self) -> int:
        """Returns the number of tests skipped"""
        return self._get_count(ResultType.EXCLUDED)

    def _get_count(self, result_type: ResultType) -> int:
        """Returns the number of tests with the given status"""
        return sum(
            1 for test in self.get_tests() if test.get_result_type() == result_type
        )
