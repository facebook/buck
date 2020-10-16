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

import uuid
from asyncio import subprocess

import pytest
from buck_api.buck_process import BuckProcess
from buck_api.buck_result import BuckResult, ExitCode


@pytest.mark.asyncio
async def test_wait():
    awaitable_process = subprocess.create_subprocess_shell(
        "echo hello", stdout=subprocess.PIPE, stderr=subprocess.PIPE
    )
    buck_build_id = str(uuid.uuid1())
    bp = BuckProcess(awaitable_process, BuckResult, "utf-8", buck_build_id)
    br: BuckResult = await bp.wait()
    assert br.stdout == "hello\n"
    assert br.buck_build_id == buck_build_id


@pytest.mark.asyncio
async def test_interrupt():
    awaitable_process = subprocess.create_subprocess_shell(
        "sleep 10", stdout=subprocess.PIPE, stderr=subprocess.PIPE
    )
    buck_build_id = str(uuid.uuid1())
    bp = BuckProcess(awaitable_process, BuckResult, "utf-8", buck_build_id)
    br = await bp.interrupt()
    assert br.get_exit_code() == ExitCode.SIGNAL_INTERRUPT
    assert br.buck_build_id == buck_build_id
