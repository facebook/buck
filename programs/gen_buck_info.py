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

from __future__ import absolute_import, division, print_function, unicode_literals

import argparse
import errno
import json
import os
import sys
import time

from programs import buck_version, buck_version_mercurial, java_version


SUPPORTED_VCS = {".git": buck_version, ".hg": buck_version_mercurial}


def main(argv):
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--release-version",
        nargs = "?",
        default = None,
        help="The buck release version")
    parser.add_argument(
        "--release-timestamp",
        nargs = "?",
        default = None,
        help="The unix timestamp when the release happened"
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
    candidate_paths = []
    vcs_module = None
    while vcs_module is None and os.path.dirname(path) != path:
        while not os.path.exists(os.path.join(path, ".buckconfig")) and os.path.dirname(path) != path:
            path = os.path.dirname(path)
        for vcs_dir, module in SUPPORTED_VCS.items():
            if os.path.exists(os.path.join(path, vcs_dir)):
                vcs_module = module
                break
        else:
            candidate_paths.append(path)
            path = os.path.dirname(path)

    if vcs_module is None:
        path = candidate_paths[0]

    if args.release_version:
        version = args.release_version
        timestamp = args.release_timestamp
        dirty = False
    elif vcs_module is not None:
        # Attempt to create a "clean" version, but fall back to a "dirty"
        # one if need be.
        version = vcs_module.get_clean_buck_version(path)
        timestamp = -1
        if version is None:
            version = vcs_module.get_dirty_buck_version(path)
        else:
            timestamp = vcs_module.get_vcs_revision_timestamp(path)
        dirty = vcs_module.is_dirty(path)
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
