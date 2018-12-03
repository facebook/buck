#!/usr/bin/env python
# Copyright 2018-present Facebook, Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may
# not use this file except in compliance with the License. You may obtain
# a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations
# under the License.

from __future__ import print_function

import logging
import os


def setup_logging():
    # Set log level of the messages to show.

    level_name = os.environ.get("BUCK_WRAPPER_LOG_LEVEL", "INFO")
    level_name_to_level = {
        "CRITICAL": logging.CRITICAL,
        "ERROR": logging.ERROR,
        "WARNING": logging.WARNING,
        "INFO": logging.INFO,
        "DEBUG": logging.DEBUG,
        "NOTSET": logging.NOTSET,
    }
    level = level_name_to_level.get(level_name.upper(), logging.INFO)
    if level == logging.INFO:
        format_string = "%(message)s"
    else:
        format_string = (
            "%(asctime)s [%(levelname)s][%(filename)s:%(lineno)d] %(message)s"
        )
    logging.basicConfig(level=level, format=format_string)
