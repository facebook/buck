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

""" A simple script to merge information into a buck_package_info json file.

This file will be packaged in the pex and read by buck_package.py.

Usage: buck_package_info.py <path_to_version_info> <path_to_resources_signature>
"""
import json
import sys


version_info = json.load(open(sys.argv[1]))
# The first line of the resources_signature file contains the main signature.
version_info["resources_signature"] = open(sys.argv[2]).readline().strip()

print(json.dumps(version_info, indent=2))
