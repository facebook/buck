#!/usr/bin/env python
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
    logging.basicConfig(
        level=level,
        format=("%(asctime)s [%(levelname)s][%(filename)s:%(lineno)d] %(message)s"),
    )
