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

import inspect
import os
import shutil
import tempfile
from functools import wraps
from pathlib import Path
from typing import Callable, Iterable, Iterator, Optional, Union

import pkg_resources
import pytest
from buck_api.buck_repo import BuckRepo


@pytest.fixture(scope="function")
def repo() -> Iterator[BuckRepo]:
    """Returns a BuckRepo for testing"""
    with tempfile.TemporaryDirectory() as temp_dir:
        test_buck_binary = os.environ["TEST_BUCK_BINARY"]
        repo = BuckRepo(
            Path(test_buck_binary),
            cwd=Path(temp_dir),
            encoding="utf-8",
            inherit_existing_env=False,
        )
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


def _copytree(
    src: Path,
    dst: Path,
    symlinks: bool = False,
    ignore: Optional[Callable[..., Iterable[str]]] = None,
) -> None:
    """Copies all files and directories from src into dst"""
    for item in os.listdir(src):
        s = src / item
        d = dst / item
        if os.path.isdir(s):
            shutil.copytree(s, d, symlinks, ignore)
        else:
            shutil.copy2(s, d)


def _buck_test_callable(fn: Callable) -> Callable:
    @pytest.mark.asyncio
    @wraps(fn)
    def wrapped(repo: BuckRepo, *inner_args, **kwargs):
        response = fn(repo, *inner_args, **kwargs)
        return response

    return wrapped


def _buck_test_not_callable(module: str, data: str) -> Callable:
    def inner_decorator(fn: Callable) -> Callable:
        @pytest.mark.asyncio
        @wraps(fn)
        def wrapped(repo: BuckRepo, *inner_args, **kwargs):
            src = Path(pkg_resources.resource_filename(module, data))
            tgt = Path(repo.cwd)
            os.makedirs(tgt, exist_ok=True)
            _copytree(src, tgt)
            response = fn(repo, *inner_args, **kwargs)
            return response

        return wrapped

    return inner_decorator


def buck_test(data: Union[Callable, str]) -> Callable:
    """
    Defines a buck test.

    data: A string for the project directory that buck will run in. Default is no project.
    """
    if callable(data):
        return _buck_test_callable(data)
    assert isinstance(data, str), data
    # Use Python's inspect to get the module name that calls buck_test.
    # We need the module name in order to use pkg_resources.resource_filename to get
    # the path to the resource specified in the BUCK file.
    # For more details, see this Stack Overflow post:
    # https://stackoverflow.com/questions/1095543/get-name-of-calling-functions-module-in-python
    frm = inspect.stack()[1]
    mod = inspect.getmodule(frm[0])
    return _buck_test_not_callable(mod.__name__, data)
