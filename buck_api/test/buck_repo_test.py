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
import tempfile
from pathlib import Path

import pkg_resources
import pytest
from buck_api.buck_repo import BuckRepo
from buck_api.buck_result import ExitCode


@pytest.mark.asyncio
async def test_build():
    with tempfile.TemporaryDirectory() as temp_dir:
        path_of_cwd = Path(temp_dir)
        test_script = pkg_resources.resource_filename(
            "buck_api.test.buck_repo_test", "test_script.py"
        )
        repo = BuckRepo(Path(test_script), cwd=path_of_cwd, encoding="utf-8")
        _create_file(path_of_cwd, "target_file_success", 0)
        result = await repo.build("//:target_file_success").wait()
        assert list(
            (path_of_cwd / "buck-out").iterdir()
        ), "build should have generated outputs in buck-out"
        assert "target_file_success" in result.get_stdout()
        assert result.is_success()


@pytest.mark.asyncio
async def test_build_with_flag():
    with tempfile.TemporaryDirectory() as temp_dir:
        path_of_cwd = Path(temp_dir)
        test_script = pkg_resources.resource_filename(
            "buck_api.test.buck_repo_test", "test_script.py"
        )
        repo = BuckRepo(Path(test_script), cwd=path_of_cwd, encoding="utf-8")
        _create_file(path_of_cwd, "target_file_success", 0)
        # test --show-output flag
        result = await repo.build("//:target_file_success --show-output").wait()
        assert result.is_success()
        target_to_output = result.get_target_to_build_output()
        target = next(iter(target_to_output.keys()))
        assert target == "//:target_file_success"
        # checks if the buck-out directory was added
        assert "buck-out/gen/" in target_to_output.get(target)


@pytest.mark.asyncio
async def test_build_with_multiple_targets_flag():
    with tempfile.TemporaryDirectory() as temp_dir:
        path_of_cwd = Path(temp_dir)
        test_script = pkg_resources.resource_filename(
            "buck_api.test.buck_repo_test", "test_script.py"
        )
        repo = BuckRepo(Path(test_script), cwd=path_of_cwd, encoding="utf-8")
        target_folder = _create_directory(path_of_cwd, Path("targets"))
        target_files = [
            "target_file_success_1",
            "target_file_success_2",
            "target_file_success_3",
        ]
        for target_file in target_files:
            _create_file(target_folder, target_file, 0)
        # test --show-output flag
        result = await repo.build("//targets/... --show-output").wait()
        assert result.is_success()
        target_to_output = result.get_target_to_build_output()
        for target_file in target_files:
            target = f"//targets:{target_file}"
            assert target in target_to_output.keys(), target_to_output
            # checks if the buck-out directory was added
            assert "buck-out/gen/" in target_to_output.get(target)
            assert target_file in target_to_output.get(target)


@pytest.mark.asyncio
async def test_build_failed():
    with tempfile.TemporaryDirectory() as temp_dir:
        path_of_cwd = Path(temp_dir)
        test_script = pkg_resources.resource_filename(
            "buck_api.test.buck_repo_test", "test_script.py"
        )
        repo = BuckRepo(Path(test_script), cwd=path_of_cwd, encoding="utf-8")

        # testing failures
        _create_file(path_of_cwd, "target_file_build_failure", 1)
        result = await repo.build("//:target_file_build_failure").wait()
        assert result.is_build_failure()

        _create_file(path_of_cwd, "target_file_failure", 13)
        result = await repo.build("//:target_file_failure").wait()
        assert result.is_failure()


@pytest.mark.asyncio
async def test_run():
    with tempfile.TemporaryDirectory() as temp_dir:
        path_of_cwd = Path(temp_dir)
        test_script = pkg_resources.resource_filename(
            "buck_api.test.buck_repo_test", "test_script.py"
        )
        repo = BuckRepo(Path(test_script), cwd=path_of_cwd, encoding="utf-8")
        _create_file(path_of_cwd, "target_file_success", 0)
        result = await repo.run("//:target_file_success").wait()
        assert list(
            (path_of_cwd / "buck-out").iterdir()
        ), "run should have generated outputs in buck-out"
        assert "run" in result.get_stdout()
        assert "target_file_success" in result.get_stdout()
        assert result.is_success()


@pytest.mark.asyncio
async def test_run_failed():
    with tempfile.TemporaryDirectory() as temp_dir:
        path_of_cwd = Path(temp_dir)
        test_script = pkg_resources.resource_filename(
            "buck_api.test.buck_repo_test", "test_script.py"
        )
        repo = BuckRepo(Path(test_script), cwd=path_of_cwd, encoding="utf-8")

        # testing failures
        _create_file(path_of_cwd, "target_file_build_failure", 1)
        result = await repo.run("//:target_file_build_failure").wait()
        assert result.is_success()

        _create_file(path_of_cwd, "target_file_failure", 13)
        result = await repo.build("//:target_file_failure").wait()
        assert result.is_failure()


@pytest.mark.asyncio
async def test_clean():
    with tempfile.TemporaryDirectory() as temp_dir:
        path_of_cwd = Path(temp_dir)
        test_script = pkg_resources.resource_filename(
            "buck_api.test.buck_repo_test", "test_script.py"
        )
        repo = BuckRepo(Path(test_script), cwd=path_of_cwd, encoding="utf-8")
        await repo.build("//:target_file").wait()
        assert list(
            (path_of_cwd / "buck-out").iterdir()
        ), "build should have generated outputs in buck-out"
        result = await repo.clean().wait()
        assert not (
            path_of_cwd / "buck-out"
        ).exists(), "clean should have deleted outputs in buck-out"
        assert result.get_exit_code() == ExitCode.SUCCESS


@pytest.mark.asyncio
async def test_kill():
    with tempfile.TemporaryDirectory() as temp_dir:
        path_of_cwd = Path(temp_dir)
        test_script = pkg_resources.resource_filename(
            "buck_api.test.buck_repo_test", "test_script.py"
        )
        repo = BuckRepo(Path(test_script), cwd=path_of_cwd, encoding="utf-8")

        await repo.build("//:target_file").wait()
        assert list(
            (path_of_cwd / ".buckd").iterdir()
        ), "build should have generated buck daemon"

        result = await repo.kill().wait()
        assert not (
            path_of_cwd / ".buckd"
        ).exists(), "kill should have deleted buck daemon"

        assert result.get_exit_code() == ExitCode.SUCCESS


@pytest.mark.asyncio
async def test_test_passed():
    with tempfile.TemporaryDirectory() as temp_dir:
        path_of_cwd = Path(temp_dir)
        test_script = pkg_resources.resource_filename(
            "buck_api.test.buck_repo_test", "test_script.py"
        )
        repo = BuckRepo(Path(test_script), cwd=path_of_cwd, encoding="utf-8")
        _create_file(path_of_cwd, "target_file_success", 0)
        result = await repo.test("//:target_file_success").wait()
        assert list(
            (path_of_cwd / "buck-out").iterdir()
        ), "test should have generated outputs in buck-out"
        assert "target_file_success" in result.get_stdout()
        assert (
            '<tests><test name="target_file_success"><testresult name="test1" status="PASS" type="SUCCESS" /></test></tests>\n'
            in result.get_stdout()
        )
        assert result.is_success()
        assert result.get_tests()[0].get_name() == "test1"
        assert result.get_success_count() == 1


@pytest.mark.asyncio
async def test_test_skipped():
    with tempfile.TemporaryDirectory() as temp_dir:
        path_of_cwd = Path(temp_dir)
        test_script = pkg_resources.resource_filename(
            "buck_api.test.buck_repo_test", "test_script.py"
        )
        repo = BuckRepo(Path(test_script), cwd=path_of_cwd, encoding="utf-8")
        # test skipped test
        _create_file(path_of_cwd, "target_file_skipped", "0")
        result = await repo.test("//:target_file_skipped").wait()
        assert list(
            (path_of_cwd / "buck-out").iterdir()
        ), "test should have generated outputs in buck-out"
        assert "target_file_skipped" in result.get_stdout()
        assert (
            '<tests><test name="target_file_skipped"><testresult name="test1" status="PASS" type="EXCLUDED" /></test></tests>'
            in result.get_stdout()
        )
        assert result.is_success()
        assert result.get_tests()[0].get_name() == "test1"
        assert result.get_skipped_count() == 1


@pytest.mark.asyncio
async def test_test_failed():
    with tempfile.TemporaryDirectory() as temp_dir:
        path_of_cwd = Path(temp_dir)
        test_script = pkg_resources.resource_filename(
            "buck_api.test.buck_repo_test", "test_script.py"
        )
        repo = BuckRepo(Path(test_script), cwd=path_of_cwd, encoding="utf-8")

        # testing failures
        _create_file(path_of_cwd, "target_file_test_failure", 32)
        result = await repo.test("//:target_file_test_failure").wait()
        assert result.is_test_failure()

        _create_file(path_of_cwd, "target_file_failure", 13)
        result = await repo.test("//:target_file_failure").wait()
        assert result.is_failure()
        assert result.get_tests()[0].get_name() == "test1"
        assert result.get_failure_count() == 1

        # testing failures
        _create_file(path_of_cwd, "target_file_build_failure", 1)
        result = await repo.test("//:target_file_build_failure").wait()
        assert result.is_build_failure()


@pytest.mark.asyncio
def test_buck_configs_context_manager():
    with tempfile.TemporaryDirectory() as temp_dir:
        path_of_cwd = Path(temp_dir)
        test_script = pkg_resources.resource_filename(
            "buck_api.test.buck_repo_test", "test_script.py"
        )
        repo = BuckRepo(Path(test_script), cwd=path_of_cwd, encoding="utf-8")
        buck_config_path = Path(temp_dir) / Path(".buckconfig")
        assert not buck_config_path.exists()
        with repo.buck_config() as buck_config:
            buck_config["a"]["b"] = "c"
        assert buck_config_path.exists()
        assert [line.strip() for line in open(buck_config_path, "r")] == [
            "[a]",
            "",
            "b = c",
            "",
        ]
        buck_config_local_path = Path(temp_dir) / Path(".buckconfig.local")
        assert not buck_config_local_path.exists()
        with repo.buck_config_local() as buck_config_local:
            buck_config_local["a"]["c"] = "d"
        assert buck_config_local_path.exists()
        assert [line.strip() for line in open(buck_config_local_path, "r")] == [
            "[a]",
            "",
            "c = d",
            "",
        ]


def _create_directory(dirpath: Path, dirname: Path) -> Path:
    """ Creates a directiroy in the given path"""
    output = dirpath / dirname
    os.makedirs(output)
    return output


def _create_file(dirpath: Path, filepath: Path, exitcode: int) -> None:
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
