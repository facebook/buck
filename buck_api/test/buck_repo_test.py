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

import tempfile
from pathlib import Path

import pkg_resources
import pytest
from buck_repo import BuckRepo


@pytest.mark.asyncio
async def test_build():
    with tempfile.TemporaryDirectory() as temp_dir:
        path_of_cwd = Path(temp_dir)
        test_script = pkg_resources.resource_filename(
            "test.buck_repo_test", "test_script.py"
        )
        repo = BuckRepo(test_script, cwd=temp_dir, encoding="utf-8")

        result = await (await repo.build("//:target_file")).wait()
        assert list(
            (path_of_cwd / Path("buck-out")).iterdir()
        ), "build should have generated outputs in buck-out"
        assert "target_file" in result.get_stdout()
        assert result.get_exit_code() == 0


@pytest.mark.asyncio
async def test_clean():
    with tempfile.TemporaryDirectory() as temp_dir:
        path_of_cwd = Path(temp_dir)
        test_script = pkg_resources.resource_filename(
            "test.buck_repo_test", "test_script.py"
        )
        repo = BuckRepo(test_script, cwd=temp_dir, encoding="utf-8")
        await (await repo.build("//:target_file")).wait()
        assert list(
            (path_of_cwd / Path("buck-out")).iterdir()
        ), "build should have generated outputs in buck-out"
        result = await (await repo.clean()).wait()
        assert not (
            path_of_cwd / Path("buck-out")
        ).exists(), "clean should have deleted outputs in buck-out"
        assert result.get_exit_code() == 0
        # TODO return something reasonable for exit code error code messages?
