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

import pkg_resources
import pytest
from repo_test import repo  # noqa: F401


@pytest.mark.usefixtures("repo")
class TestBuckRepo:
    test_script = pkg_resources.resource_filename(
        "buck_e2e.repo_end_to_end_test", "test_script.py"
    )
    os.environ["TEST_BUCK_BINARY"] = test_script

    @staticmethod
    @pytest.mark.asyncio
    async def test_repo_build(repo):
        result = await (await repo.build("//:target_file_success")).wait()
        assert "target_file_success" in result.get_stdout()
