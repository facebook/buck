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

import pytest
from buck_api.buck_config import BuckConfig


@pytest.mark.asyncio
def test_empty_config():
    with tempfile.TemporaryDirectory() as temp_dir:
        buck_config_path = Path(temp_dir) / Path(".buckconfig.local")
        BuckConfig(buck_config_path)
        assert buck_config_path.exists(), buck_config_path
        assert [line.strip() for line in open(buck_config_path, "r")] == []


@pytest.mark.asyncio
def test_context_manager_config():
    with tempfile.TemporaryDirectory() as temp_dir:
        buck_config_path = Path(temp_dir) / Path(".buckconfig.local")
        buck_config = BuckConfig(buck_config_path)
        assert buck_config_path.exists(), buck_config_path
        with buck_config.modify() as config:
            config["a"]["b"] = "c"
            assert [line.strip() for line in open(buck_config_path, "r")] == []
            config["a"]["b"] = "d"
            assert [line.strip() for line in open(buck_config_path, "r")] == []
            config["a"]["c"] = "e"
            assert [line.strip() for line in open(buck_config_path, "r")] == []
        assert [line.strip() for line in open(buck_config_path, "r")] == [
            "[a]",
            "",
            "b = d",
            "c = e",
            "",
        ]
        with buck_config.modify() as config:
            del config["a"]["b"]
        assert [line.strip() for line in open(buck_config_path, "r")] == [
            "[a]",
            "",
            "c = e",
            "",
        ]
