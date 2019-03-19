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

import argparse
import errno
import json
import os
import sys
import time

import buck_version
import java_version


def main(argv):
    parser = argparse.ArgumentParser()
    parser.add_argument("--release-version", help="The buck release version")
    parser.add_argument(
        "--release-timestamp", help="The unix timestamp when the release happened"
    )
    parser.add_argument(
        "--java-version",
        help="The Java version buck was compiled against",
        required=True,
    )
    args = parser.parse_args(argv[1:])
    if bool(args.release_version) != bool(args.release_timestamp):
        print(
            "--release-version and --release-timestamp must either both be "
            "set, or neither can be set"
        )
        sys.exit(1)

    # Locate the root of the buck repo.  We'll need to be there to
    # generate the buck version UID.
    path = os.getcwd()
    while not os.path.exists(os.path.join(path, ".buckconfig")):
        path = os.path.dirname(path)

    if args.release_version:
        version = args.release_version
        timestamp = args.release_timestamp
        dirty = False
    elif os.path.exists(os.path.join(path, ".git")):
        # Attempt to create a "clean" version, but fall back to a "dirty"
        # one if need be.
        version = buck_version.get_clean_buck_version(path)
        timestamp = -1
        if version is None:
            version = buck_version.get_dirty_buck_version(path)
        else:
            timestamp = buck_version.get_git_revision_timestamp(path)
        dirty = buck_version.is_dirty(path)
    else:
        # We're building outside a git repo. Check for the special
        # .buckrelease file created by the release process.
        try:
            with open(os.path.join(path, ".buckrelease")) as f:
                timestamp = int(os.fstat(f.fileno()).st_mtime)
                version = f.read().strip()
        except IOError as e:
            if e.errno == errno.ENOENT:
                # No .buckrelease file. Do the best that we can.
                version = "(unknown version)"
                timestamp = int(time.time())
            else:
                raise e
        dirty = False

    json.dump(
        {
            "version": version,
            "timestamp": timestamp,
            "is_dirty": dirty,
            "java_version": java_version.get_java_major_version(args.java_version),
        },
        sys.stdout,
        sort_keys=True,
        indent=2,
    )


sys.exit(main(sys.argv))
