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

from pathlib import Path

from buck_api.buck_repo import BuckRepo
from buck_api.buck_result import ExitCode
from buck_e2e.repo_workspace import buck_test, nobuckd, repo  # noqa F401


@buck_test(data="testdata/cli/run/simple_bin")
async def test_repo_build(repo: BuckRepo):
    result = await repo.build(":main").wait()
    assert result.is_success()


@buck_test(data="testdata/cli/run/simple_bin")
@nobuckd
async def test_buckd_toggle_disabled(repo: BuckRepo):
    result = await repo.build(":main").wait()
    assert result.is_success()
    assert not (Path(repo.cwd) / ".buckd").exists(), "buck daemon should not exist"
    assert result.get_exit_code() == ExitCode.SUCCESS
