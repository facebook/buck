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

import signal
from asyncio import subprocess
from typing import Awaitable, Callable, Generic, TypeVar


T = TypeVar("T")


class BuckProcess(Generic[T]):
    """ Instiates a BuckProcess object with a new process """

    def __init__(
        self,
        awaitable_process: Awaitable[subprocess.Process],
        result_type: Callable[[subprocess.Process, bytes, bytes, str, str], T],
        encoding: str,
        buck_build_id: str,
    ) -> None:
        self._awaitable_process = awaitable_process
        self._result_type = result_type
        self._encoding = encoding
        self.buck_build_id = buck_build_id

    async def _get_result(self, process: subprocess.Process) -> T:
        stdout, stderr = await process.communicate()
        return self._result_type(
            process, stdout, stderr, self._encoding, self.buck_build_id
        )

    async def wait(self) -> T:
        """ Returns a BuckResult with a finished process """
        process = await self._awaitable_process
        return await self._get_result(process)

    async def interrupt(self) -> T:
        """ Sends SIGINT, and returns a BuckResult with an interrupted process """
        process = await self._awaitable_process
        process.send_signal(signal.SIGINT)
        return await self._get_result(process)
