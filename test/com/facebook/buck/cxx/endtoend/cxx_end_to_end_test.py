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

from xplat.build_infra.buck_e2e.api.buck_repo import BuckRepo
from xplat.build_infra.buck_e2e.asserts import assert_parse_error, expect_failure
from xplat.build_infra.buck_e2e.repo_workspace import (
    buck_test,
    exec_path,
)


# TODO: Add decorator to run this with and without buckd
@buck_test(data="testdata/cxx")
async def test_should_build_and_run_successfully(repo: BuckRepo):
    result = await repo.build(
        "//simple_successful_helloworld:simple_successful_helloworld", "--show-output"
    )

    target_to_build_output = result.get_target_to_build_output()
    assert len(target_to_build_output) == 1

    build_output = next(iter(target_to_build_output.values()))
    process = await exec_path(repo.cwd / build_output)
    assert process.returncode == 0


@buck_test(data="testdata/cxx")
async def test_successful_env_empty_file(repo: BuckRepo):
    build_failure = await expect_failure(
        repo.build("//simple_parse_error_helloworld:simple_parse_error_helloworld")
    )
    assert_parse_error(build_failure)


@buck_test(data="testdata/cxx")
async def test_should_not_build_successfully(repo: BuckRepo):
    await expect_failure(
        repo.build("//simple_failed_helloworld:simple_failed_helloworld")
    )
