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

# Runs pytest with the modules in __test_modules__.py in a PEX file, while
# excluding __test_main__.py (which is this module).

import json
import logging
import os
import pkgutil
import re
import sys
import tempfile
import xml.etree.ElementTree as ET
from contextlib import redirect_stderr, redirect_stdout
from enum import IntEnum
from io import StringIO
from typing import Any, Dict, List, Optional, Tuple

import __test_main__
import pytest  # type: ignore

# This variable contains the test modules in the PEX file for a test
from __test_modules__ import TEST_MODULES  # type: ignore


PYTEST_TEST_CASE_NAME = __name__
PYTEST_TEST_CASE = "pytest"


# Copy of ExitCode defined in pytest at:
# https://github.com/pytest-dev/pytest/blob/7e5ad314287a5c86856efea13c04333d7baf3643/src/_pytest/main.py#L23
# Redefining here because of lack of access to _pytest.main
class ExitCode(IntEnum):
    OK = 0
    TESTS_FAILED = 1
    INTERRUPTED = 2
    INTERNAL_ERROR = 3
    USAGE_ERROR = 4
    NO_TESTS_COLLECTED = 5


class TestDiscoveryException(Exception):
    def __init__(self, exit_code: ExitCode):
        self.exit_code = exit_code

    def exit_code(self) -> ExitCode:
        return self.exit_code


class TestCollector:
    """
    a pytest plugin to be used to collect all the pytest items
    """

    def __init__(self):
        self.collect = []

    def get_collected(self) -> List[pytest.Item]:
        return self.collect

    def pytest_collection_modifyitems(self, items: List[pytest.Item]):
        self.collect.extend(items)


class PytestMainProgram(__test_main__.MainProgram):
    def __init__(self, argv):
        super().__init__(argv)

        # TODO really, we should have buck test run this test saying list style
        # is buck instead of trying to infer based on output options
        if self.options.output is not None or self.options.list_format == "buck":
            self.is_buck_test = True
        else:
            self.is_buck_test = False

    def main(self) -> ExitCode:
        """
        Main entry point for a pytest test suite.
        """

        if self.options.collect_coverage:
            raise Exception("Coverage is not supported for pytest")

        if self.options.list:
            exit_code = self.list_tests()
        else:
            exit_code = self.run_tests()

        if exit_code == ExitCode.OK or exit_code == ExitCode.NO_TESTS_COLLECTED:
            return __test_main__.EXIT_CODE_SUCCESS
        else:
            return __test_main__.EXIT_CODE_TEST_FAILURE

    def _buck_format_test_item(self, item: pytest.Item) -> str:
        """
        formats a pytest item for printing, either in pytest standard format,
        or buck format
        """
        if self.is_buck_test:
            return "{}#{}".format(item.parent.nodeid, item.name)
        else:
            # testpilot python runner expects this weird format
            return "{1} ({0})".format(item.parent.nodeid, item.name)

    def _get_tests_to_run(self) -> List[str]:
        """
        gets a list of tests to run based on the given regex filters, as a set of
        """

        if self.options.regex is None and not self.test_args:
            return self._get_all_modules_args()

        tests, exit_code = self._list_tests()

        if exit_code != ExitCode.OK:
            raise TestDiscoveryException(exit_code)

        test_regex = re.compile(self.options.regex) if self.options.regex else None

        def matcher(test: pytest.Item) -> bool:
            # regex matches are <testcase>#<testname> used by buck
            regex_name = self._buck_format_test_item(test)

            # testpilot matches are based on <testcase>.<testname>
            test_name = "{0}.{1}".format(test.parent.nodeid, test.name)

            is_match = True
            if test_regex:
                is_match = is_match and test_regex.match(regex_name)

            if self.test_args:
                is_match = is_match and test_name in self.test_args

            return is_match

        return [test.nodeid for test in tests if matcher(test)]

    def list_tests(self) -> ExitCode:
        tests, exit_code = self._list_tests()

        for test in tests:
            print(self._buck_format_test_item(test))

        return exit_code

    def _list_tests(self) -> Tuple[List[pytest.Item], ExitCode]:
        """
        gets all the pytest items in the current module
        """
        pytest_args = ["--collect-only", "-q"]
        pytest_args.extend(self._get_pytest_args(self._get_all_modules_args()))

        plugin = TestCollector()
        test_stdout = StringIO()
        test_stderr = StringIO()
        with redirect_stdout(test_stdout):
            with redirect_stderr(test_stderr):
                exit_code = pytest.main(pytest_args, plugins=[plugin])

        return plugin.get_collected(), exit_code

    def run_tests(self) -> ExitCode:
        """
        runs the pyunit tests based on the supplied regex filters
        """

        _, junit_filepath = tempfile.mkstemp()
        exit_code = ExitCode.INTERNAL_ERROR

        try:
            pytest_args = [
                "--junit-xml",
                junit_filepath,
                "--override-ini",
                "junit_logging=out-err",
            ]
            tests_to_run = self._get_tests_to_run()

            if tests_to_run:
                pytest_args += self._get_pytest_args(tests_to_run)

                if self.is_buck_test:
                    test_stdout = StringIO()
                    test_stderr = StringIO()
                    with redirect_stdout(test_stdout):
                        with redirect_stderr(test_stderr):
                            exit_code = pytest.main(pytest_args)
                else:
                    pytest_args += sys.argv[1:]
                    exit_code = pytest.main(pytest_args)
                    return exit_code

                results = self._parse_output(
                    ExitCode(exit_code), junit_filepath, test_stderr.getvalue()
                )
            else:
                results = self._parse_empty_output(ExitCode.OK, "")

        except TestDiscoveryException as e:
            logging.error("failed to discover tests")
            exit_code = e.exit_code()
        finally:
            try:
                os.remove(junit_filepath)
            except IOError:
                logging.warn(
                    f"Could not remove temporary test results file {junit_filepath}"
                )

        if (
            self.is_buck_test
            and self.options.output is not None
            and results is not None
        ):
            with open(self.options.output, "w") as f:
                json.dump(results, f, indent=4, sort_keys=True)

        return exit_code

    def _get_all_modules_args(self) -> List[str]:
        args = []
        for module in TEST_MODULES:
            if module.endswith("__test__main__"):
                continue

            try:
                pkgutil.find_loader(module)
            except ImportError:
                # pytest swallows this ImportError without actionable output.
                # In this case, run against the file path so the error is visible.
                module_path = os.path.join(*module.split("."))
                if not os.path.isdir(module_path):
                    # If module_path is not a directory, it must be the tests file.
                    module_path += ".py"
                args.append(module_path)
            else:
                args.append(module)

        return args

    def _get_pytest_args(self, tests: List[str]) -> List[str]:

        args = ["--pyargs"]

        if self.options.failfast:
            args.append("-x")

        args.extend(tests)

        return args

    def _parse_output(
        self, exit_code: ExitCode, junit_filepath: str, test_stderr: str
    ) -> List[Dict[str, Any]]:
        root = ET.parse(junit_filepath).getroot()

        testsuites = root.findall("testsuite")

        testcases = []
        for suite in testsuites:
            testcases.extend(suite.findall("testcase"))

        if testcases:
            return self._parse_testcases(testcases)
        else:
            return self._parse_empty_output(exit_code, test_stderr)

    def _parse_empty_output(
        self, exit_code: ExitCode, test_stderr: str
    ) -> List[Dict[str, object]]:
        modules = [
            module for module in TEST_MODULES if not module.endswith("__test_main__")
        ]

        classname = "<ALL>"
        results = []

        duration = 0
        if exit_code == ExitCode.USAGE_ERROR:
            status = __test_main__.TestStatus.ABORTED
            message = test_stderr.strip()
        elif exit_code == ExitCode.NO_TESTS_COLLECTED:
            status = __test_main__.TestStatus.UNEXPECTED_SUCCESS
            message = f"[{exit_code.name}]\n{test_stderr.strip()}"
        elif exit_code == ExitCode.OK:
            status = __test_main__.TestStatus.EXCLUDED
            message = "[No tests found]"
        else:
            status = __test_main__.TestStatus.FAILED
            message = f"[{exit_code.name}]\n{test_stderr.strip()}"

        for name in modules:
            results.append(
                {
                    "testCaseName": classname,
                    "testCase": name,
                    "time": duration,
                    "type": status,
                    "message": message,
                }
            )
        return results

    def _parse_testcases(self, testcases: List[ET.Element]) -> List[Dict[str, object]]:
        results = []
        for testcase in testcases:
            classname = testcase.attrib["classname"].strip()
            if not classname:
                classname = "<ALL>"
            name = testcase.attrib["name"].strip()
            duration = int(float(testcase.attrib["time"]) * 1000)
            error = testcase.find("error")
            failure = testcase.find("failure")
            skipped = testcase.find("skipped")
            out = self._parse_captured(testcase.find("system-out"))
            err = self._parse_captured(testcase.find("system-err"))
            if error is not None:
                status = __test_main__.TestStatus.ABORTED
                message = self._get_message(error)
            elif failure is not None:
                status = __test_main__.TestStatus.FAILED
                message = self._get_message(failure)
            elif skipped is not None:
                status = __test_main__.TestStatus.SKIPPED
                message = self._get_message(skipped)
            else:
                status = __test_main__.TestStatus.PASSED
                message = ""

            results.append(
                {
                    "testCaseName": classname,
                    "testCase": name,
                    "time": duration,
                    "type": status,
                    "message": message,
                    "stdOut": out,
                    "stdErr": err,
                }
            )
        return results

    def _get_message(self, element: ET.Element) -> str:
        message = ""
        if "message" in element.attrib:
            message += f"[{element.attrib['message'].strip()}]"
        if element.text is not None:
            if message:
                message += "\n"
            message += element.text.strip()
        return message

    def _parse_captured(self, element: Optional[ET.Element]) -> str:
        message = ""

        # pytest auto adds a these tokens for stderr and stdout. We'll remove
        # it since Buck prints will add more tokens
        if element is not None:
            if element.text is not None:
                message = element.text.replace(
                    (
                        "--------------------------------- Captured Err "
                        "---------------------------------\n"
                    ),
                    "",
                ).replace(
                    (
                        "--------------------------------- Captured Out "
                        "---------------------------------\n"
                    ),
                    "",
                )
        return message


PytestMainProgram(sys.argv).main()
