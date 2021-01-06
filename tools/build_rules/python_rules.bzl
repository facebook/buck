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

"""Module containing Python macros."""

def interpreter_override_args():
    """Determine interpreter override arguments based on a buck config.

    For example, `--config user.buck_pex_interpreter=python2` will generate a pex
    which invokes `python2` instead of the default, `python<major>.<minor>`.

    Returns:
      a list of arguments to pass to python interpreter.
    """
    val = native.read_config("user", "buck_pex_interpreter", "")
    if val != "":
        return ["--python-shebang", "/usr/bin/env " + val]
    else:
        return []
