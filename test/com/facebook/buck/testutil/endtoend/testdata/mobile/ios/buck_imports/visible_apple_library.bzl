# Copyright 2018-present, Facebook, Inc.
# All rights reserved.
#
# This source code is licensed under the license found in the
# LICENSE file in the root directory of this source tree.

"""Exposes an auto-public version of apple_library. We use this to test loading macros"""

def visible_apple_library(**kwargs):
    """apple_library that is by default public"""
    kwargs["visibility"] = ["PUBLIC"]
    apple_library(**kwargs)
