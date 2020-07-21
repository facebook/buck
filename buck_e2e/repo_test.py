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

import pytest
from buck_repo import BuckRepo


@pytest.fixture()
def repo() -> BuckRepo:
    """Returns a BuckRepo for testing"""

    with tempfile.TemporaryDirectory() as temp_dir:
        test_buck_binary: str = os.environ["TEST_BUCK_BINARY"]
        repo: BuckRepo = BuckRepo(test_buck_binary, cwd=temp_dir, encoding="utf-8")
        # setup
        yield repo
        # teardown
