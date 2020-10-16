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

import textwrap
from pathlib import Path

import pytest
from buck_api.buck_repo import BuckRepo
from buck_api.buck_result import ExitCode
from buck_e2e import repo_workspace
from buck_e2e.repo_workspace import buck_test, nobuckd, repo  # noqa: F401


@buck_test
async def test_repo_build(repo: BuckRepo):
    _create_file(Path(repo.cwd), Path("target_file_success"), 0)
    result = await repo.build("//:target_file_success").wait()
    assert "target_file_success" in result.stdout
    assert result.is_success()


@buck_test
async def test_buckd_toggle_enabled(repo: BuckRepo):
    _create_file(Path(repo.cwd), Path("target_file_success"), 0)
    result = await repo.build("//:target_file_success").wait()
    assert "target_file_success" in result.stdout
    assert result.is_success()
    assert (Path(repo.cwd) / ".buckd").exists(), "buck daemon should exist"
    assert result.get_exit_code() == ExitCode.SUCCESS


@buck_test
@repo_workspace.nobuckd
async def test_buckd_toggle_disabled(repo: BuckRepo):
    _create_file(Path(repo.cwd), Path("target_file_success"), 0)
    result = await repo.build("//:target_file_success").wait()
    assert "target_file_success" in result.stdout
    assert result.is_success()
    assert not (Path(repo.cwd) / ".buckd").exists(), "buck daemon should not exist"
    assert result.get_exit_code() == ExitCode.SUCCESS


@buck_test
async def test_repo_build_failure(repo: BuckRepo):
    _create_file(Path(repo.cwd), Path("target_file_failure"), 1)
    result = await repo.build("//:target_file_failure").wait()
    assert "target_file_failure" in result.stdout
    assert result.is_failure()


@pytest.mark.xfail(raises=AssertionError)
@buck_test(data="testdata/has_buck_config_local")
async def test_copying_buck_config_local_fails(repo: BuckRepo):
    # Test checking of .buckconfig.local with buck_test decorator.
    pass


@buck_test
async def test_repo_default_config(repo: BuckRepo):
    buck_config_local_path = Path(repo.cwd / ".buckconfig.local")
    assert buck_config_local_path.exists()
    assert buck_config_local_path.read_text() == textwrap.dedent(
        """\
        [buildfile]

        name = BUCK.fixture

        [log]

        scuba_logging = false
        everstore_log_upload_mode = never
        scribe_offline_enabled = false

        """
    )


def _create_file(dirpath: Path, filepath: Path, exitcode: int):
    """ Writes out a message to a file given the path"""
    with open(dirpath / filepath, "w") as f1:
        target_name = str(filepath)
        status = "FAIL" if "failure" in target_name else "PASS"
        result_type = (
            "FAILURE"
            if "fail" in target_name
            else ("SUCCESS" if "success" in target_name else "EXCLUDED")
        )
        message = f"{target_name}\n{status}\n{result_type}\n{exitcode}"
        f1.write(message)
