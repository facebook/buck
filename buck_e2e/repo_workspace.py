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
from functools import wraps
from pathlib import Path
from typing import Callable, Iterator

import pytest
from buck_api.buck_repo import BuckRepo


@pytest.fixture(scope="function")
def repo() -> Iterator[BuckRepo]:
    """Returns a BuckRepo for testing"""
    with tempfile.TemporaryDirectory() as temp_dir:
        test_buck_binary = os.environ["TEST_BUCK_BINARY"]
        repo = BuckRepo(Path(test_buck_binary), cwd=Path(temp_dir), encoding="utf-8")
        with repo.buck_config() as buck_config:
            buck_config["buildfile"]["name"] = "BUCK.fixture"
        with repo.buck_config_local() as buck_config_local:
            buck_config_local["log"]["scuba_logging"] = "false"
            buck_config_local["log"]["everstore_log_upload_mode"] = "never"
            buck_config_local["log"]["scribe_offline_enabled"] = "false"
        yield repo
        # teardown


def nobuckd(fn: Callable) -> Callable:
    """Disables buck daemon"""

    @wraps(fn)
    def wrapped(repo: BuckRepo, *args, **kwargs):
        repo.set_buckd(True)
        return fn(repo, *args, **kwargs)

    return wrapped


def _buck_test_callable(fn: Callable) -> Callable:
    @pytest.mark.asyncio
    @wraps(fn)
    def wrapped(repo: BuckRepo, *inner_args, **kwargs):
        response = fn(repo, *inner_args, **kwargs)
        return response

    return wrapped


def buck_test(*args, **kwargs) -> Callable:
    """
    Defines a buck test.

    data: A string for the project directory that buck will run in. Default is no project.
    """
    if len(args) == 1 and callable(args[0]):
        return _buck_test_callable(args[0])
    raise NotImplementedError("@buck_test(data) has not been implemented")
