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

"""Contains build rules for supporting Buck modules in tests"""

def convert_module_deps_to_test(deps):
    """ Converts the given module dependencies to module dependencies for testing """
    converted_deps = []
    for dep in deps:
        converted_deps.append(dep + "_module_for_test")
    return converted_deps
