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

""" Functions for BUCK file in this package """

def get_release_version():
    """ Gets the release version from the command line or "" """
    return native.read_config("buck", "release_version", "")

def get_release_timestamp():
    """ Gets the release version from the command line or "" """
    return native.read_config("buck", "release_timestamp" ,"")

def get_gen_buck_info_command(gen_buck_info_target):
    """
    Gets the gen_buck_info command to run based on configuration

    Args:
        gen_buck_info_target: The target that contains gen_buck_info script

    Returns:
        The cmd string to run
    """
    version = get_release_version()
    timestamp = get_release_timestamp()
    java_version = native.read_config("java", "target_level")
    if version and timestamp:
        return (
            '$(exe {target}) --release-version {version} ' +
            '--release-timestamp {timestamp} --java-version {java_version} > "$OUT"'
        ).format(target=gen_buck_info_target, version=version, timestamp=timestamp, java_version=java_version)
    else:
        return "$(exe {target}) --java-version {java_version} > $OUT".format(target=gen_buck_info_target, java_version=java_version)
