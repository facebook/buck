# Copyright 2018-present, Facebook, Inc.
# All rights reserved.
#
# This source code is licensed under the license found in the
# LICENSE file in the root directory of this source tree.

"""Exposes an auto-public version of android_library"""

def visible_android_library(**kwargs):
    """android_library that is by default public"""
    kwargs["visibility"] = ["PUBLIC"]
    android_library(**kwargs)
