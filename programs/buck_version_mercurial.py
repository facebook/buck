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

from __future__ import print_function

import hashlib
import os
import re
import subprocess
import sys
import tempfile
from subprocess import CalledProcessError, check_output

from programs.subprocutils import which


def is_vcs(dirpath):  # type: (str) -> bool
    dot_hg = os.path.join(dirpath, ".hg")
    if which("hg") and sys.platform != "cygwin":
        if os.path.exists(dot_hg) and os.path.isdir(dot_hg):
            return True
        try:
            with open(os.devnull, "w") as devnull:
                output = check_output(
                    ["hg", "root"], cwd=dirpath, stderr=devnull
                ).decode("utf-8")
            return os.path.abspath(output.strip()) == os.path.abspath(dirpath)
        except CalledProcessError:
            pass
    return False


def is_dirty(dirpath):  # type: (str) -> bool
    # Ignore any changes under these paths for the purposes of forcing a rebuild
    # of Buck itself.
    IGNORE_PATHS = ["test"]
    IGNORE_PATHS_RE_GROUP = "|".join([re.escape(e) for e in IGNORE_PATHS])
    IGNORE_PATHS_RE = re.compile("^\S+ (?:" + IGNORE_PATHS_RE_GROUP + ")")

    if not is_vcs(dirpath):
        return False

    output = check_output(["hg", "status"], cwd=dirpath).decode("utf-8")
    output = "\n".join(
        [line for line in output.splitlines() if not IGNORE_PATHS_RE.search(line)]
    )
    return bool(output.strip())


def get_vcs_revision(dirpath):  # type: (str) -> str
    output = check_output(["hg", "log", "-l1", "-T{node}", "-r."], cwd=dirpath).decode(
        "utf-8"
    )
    return output.splitlines()[0].strip()


def get_vcs_revision_timestamp(dirpath):  # type: (str) -> str
    return (
        check_output(["hg", "log", "-l1", "-T{date|hgdate}", "-r."], cwd=dirpath)
        .decode("utf-8")
        .strip()
        .split()
        .pop(0)
    )


def get_clean_buck_version(dirpath, allow_dirty=False):  # type: (str, bool) -> str
    if not is_vcs(dirpath):
        return "N/A"
    if allow_dirty or not is_dirty(dirpath):
        return get_vcs_revision(dirpath)


def get_dirty_buck_version(dirpath):  # type: (str) -> str
    hasher = hashlib.sha1()

    hasher.update(get_vcs_revision(dirpath).encode("utf-8"))

    output = check_output(["hg", "status"], cwd=dirpath).decode("utf-8")
    for line in output.splitlines():
        # include modificaton style and filename in hash
        hasher.update(line.encode("utf-8"))
        parts = line.split(" ", 1)
        if len(parts) != 2:
            continue
        try:
            with open(os.path.normpath(os.path.join(dirpath, parts[1])), "rb") as fh:
                # Only hash 512mb max
                hasher.update(fh.read(512 * 1024 * 1024))
        except IOError:
            # For deleted files, we just rely on the type, filename hash above
            pass

    return hasher.hexdigest()
