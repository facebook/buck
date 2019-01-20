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

from __future__ import print_function

import os
import re
import subprocess
import sys
import tempfile
from subprocess import CalledProcessError, check_output

from subprocutils import which


class EmptyTempFile(object):
    def __init__(self, prefix=None, dir=None, closed=True):
        self.file, self.name = tempfile.mkstemp(prefix=prefix, dir=dir)
        if closed:
            os.close(self.file)
        self.closed = closed

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.close()
        os.remove(self.name)

    def close(self):
        if not self.closed:
            os.close(self.file)
        self.closed = True

    def fileno(self):
        return self.file


def is_git(dirpath):  # type: (str) -> bool
    dot_git = os.path.join(dirpath, ".git")
    if which("git") and sys.platform != "cygwin":
        if os.path.exists(dot_git) and os.path.isdir(dot_git):
            return True
        try:
            with open(os.devnull, "w") as devnull:
                output = check_output(
                    ["git", "rev-parse", "--is-inside-work-tree"],
                    cwd=dirpath,
                    stderr=devnull,
                ).decode("utf-8")
            return output.strip() == "true"
        except CalledProcessError:
            pass
    return False


def is_dirty(dirpath):  # type: (str) -> bool
    # Ignore any changes under these paths for the purposes of forcing a rebuild
    # of Buck itself.
    IGNORE_PATHS = ["test"]
    IGNORE_PATHS_RE_GROUP = "|".join([re.escape(e) for e in IGNORE_PATHS])
    IGNORE_PATHS_RE = re.compile("^.. (?:" + IGNORE_PATHS_RE_GROUP + ")")

    if not is_git(dirpath):
        return False

    output = check_output(["git", "status", "--porcelain"], cwd=dirpath).decode("utf-8")
    output = "\n".join(
        [line for line in output.splitlines() if not IGNORE_PATHS_RE.search(line)]
    )
    return bool(output.strip())


def get_git_revision(dirpath):  # type: (str) -> str
    output = check_output(["git", "rev-parse", "HEAD", "--"], cwd=dirpath).decode(
        "utf-8"
    )
    return output.splitlines()[0].strip()


def get_git_revision_timestamp(dirpath):  # type: (str) -> str
    return (
        check_output(
            ["git", "log", "--pretty=format:%ct", "-1", "HEAD", "--"], cwd=dirpath
        )
        .decode("utf-8")
        .strip()
    )


def get_clean_buck_version(dirpath, allow_dirty=False):  # type: (str, bool) -> str
    if not is_git(dirpath):
        return "N/A"
    if allow_dirty or not is_dirty(dirpath):
        return get_git_revision(dirpath)


def get_dirty_buck_version(dirpath):  # type: (str) -> str
    git_tree_in = (
        check_output(
            ["git", "log", "-n1", "--pretty=format:%T", "HEAD", "--"], cwd=dirpath
        )
        .decode("utf-8")
        .strip()
    )

    with EmptyTempFile(prefix="buck-git-index") as index_file:
        new_environ = os.environ.copy()
        new_environ["GIT_INDEX_FILE"] = index_file.name
        subprocess.check_call(
            ["git", "read-tree", git_tree_in], cwd=dirpath, env=new_environ
        )

        subprocess.check_call(["git", "add", "-A"], cwd=dirpath, env=new_environ)

        git_tree_out = (
            check_output(["git", "write-tree"], cwd=dirpath, env=new_environ)
            .decode("utf-8")
            .strip()
        )

    with EmptyTempFile(prefix="buck-version-uid-input", closed=False) as uid_input:
        subprocess.check_call(
            ["git", "ls-tree", "--full-tree", git_tree_out],
            cwd=dirpath,
            stdout=uid_input,
        )
        return (
            check_output(["git", "hash-object", uid_input.name], cwd=dirpath)
            .decode("utf-8")
            .strip()
        )
