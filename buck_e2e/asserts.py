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

from buck_api.buck_result import BuckResult, BuildResult, ExitCode


def _get_error_msg(result: BuckResult, error_msg: str) -> str:
    return error_msg or (
        f"exit_code: {result.get_exit_code()}\n"
        f"stdout: {result.stdout}\n"
        f"stderr: {result.stderr}"
    )


def success(result: BuckResult, error_msg: str = "") -> None:
    assert result.is_success(), _get_error_msg(result, error_msg)


def failure(result: BuckResult, error_msg: str = "") -> None:
    assert result.is_failure(), _get_error_msg(result, error_msg)


def parse_error(result: BuildResult, error_msg: str = "") -> None:
    assert result.get_exit_code() == ExitCode.PARSE_ERROR, _get_error_msg(
        result, error_msg
    )
