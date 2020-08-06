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

import errno
import hashlib
import logging
import os
import shutil
import subprocess
import sys
import tempfile
import textwrap

from programs import file_locks
from programs.tracing import Tracing


try:
    USER_NAME = os.getlogin()
except (AttributeError, OSError):
    import getpass

    USER_NAME = getpass.getuser()


def get_file_contents_if_exists(path, default=None):
    with Tracing("BuckProject.get_file_contents_if_it_exists", args={"path": path}):
        if not os.path.exists(path):
            return default
        with open(path) as f:
            contents = f.read().strip()
            return default if not contents else contents


def write_contents_to_file(path, contents):
    with Tracing("BuckProject.write_contents_to_file", args={"path": path}):
        with open(path, "w") as output_file:
            output_file.write(str(contents))


def makedirs(path):
    try:
        os.makedirs(path)
    except OSError as e:
        # Potentially the case that multiple processes are running in parallel
        # (e.g. a series of linters running buck query without buckd), so we
        # should just swallow the error.
        # This is mostly equivalent to os.makedirs(path, exist_ok=True) in
        # Python 3.
        if e.errno == errno.EEXIST and os.path.isdir(path):
            return
        raise


def _is_eden(path):
    return os.path.isdir(os.path.join(path, ".eden", "root"))


def _find_eden_root(project_root):
    return os.readlink(os.path.join(project_root, ".eden", "root"))


def _add_eden_bindmount(eden_root, path):
    relative_path = os.path.relpath(path, eden_root)
    logging.debug(
        "Adding eden mount at {}, path relative to eden {}".format(path, relative_path)
    )
    try:
        subprocess.check_output(["eden", "redirect", "add", relative_path, "bind"])
    except subprocess.CalledProcessError:
        logging.warning("Could not add eden redirect for " + path)
        raise


def add_eden_bindmounts(repo_root, buck_out):
    if not _is_eden(repo_root):
        return

    add_bindmounts = os.environ.get("NO_BUCK_ADD_EDEN_BINDMOUNTS", "0").strip() == "0"
    if not add_bindmounts:
        logging.warning(
            "Skipping adding eden bindmounts because "
            + "NO_BUCK_ADD_EDEN_BINDMOUNTS was set"
        )
        return

    eden_root = _find_eden_root(repo_root)

    eden_bindmounts = {buck_out}
    eden_bindmounts_file = os.path.join(repo_root, ".buck-eden-bindmounts")
    if os.path.exists(eden_bindmounts_file):
        logging.debug("Reading eden bindmounts from " + eden_bindmounts_file)
        with open(eden_bindmounts_file, "r") as fin:
            for bindmount in fin:
                bindmount = bindmount.strip()
                if bindmount and not bindmount.startswith("#"):
                    eden_bindmounts.add(os.path.join(repo_root, bindmount))

    for mount in eden_bindmounts:
        if os.path.exists(mount):
            if _is_eden(mount):
                msg = (
                    "Eden bindmount at {path} was requested, but it is already a "
                    "directory within an eden filesystem.\n"
                    "In order to prevent destructive actions on user data, you "
                    "must remove this directory yourself.\n"
                    "Please stop buck with `buck killall`, remove {path}, and run buck "
                    "again."
                )
                logging.warning(msg.format(path=mount))
            else:
                logging.debug(
                    "Eden bindmount at {} already exists, skipping".format(mount)
                )
        else:
            _add_eden_bindmount(eden_root, mount)


class BuckProject:
    def __init__(self, root):
        self.root = root
        try:
            isolated_pos = sys.argv.index("--isolation_prefix")
            if isolated_pos < len(sys.argv):
                self.prefix = sys.argv[isolated_pos + 1]
            else:
                self.prefix = ""
        except ValueError:
            self.prefix = ""

        self._buck_out_dirname = "buck-out"
        if len(self.prefix) > 0:
            self._buck_out_dirname = self.prefix + "-" + self._buck_out_dirname

        self._buck_out = os.path.join(self.root, self._buck_out_dirname)
        add_eden_bindmounts(self.root, self._buck_out)

        self._buck_out_tmp = os.path.join(self._buck_out, "tmp")
        makedirs(self._buck_out_tmp)
        self._buck_out_log = os.path.join(self._buck_out, "log")
        makedirs(self._buck_out_log)
        self.tmp_dir = tempfile.mkdtemp(prefix="buck_run.", dir=self._buck_out_tmp)

        # Only created if buckd is used.
        self.buckd_tmp_dir = None

        self.buckd_dir = os.path.join(root, self.prefix + ".buckd")
        self.buckd_version_file = os.path.join(self.buckd_dir, "buckd.version")
        self.buckd_pid_file = os.path.join(self.buckd_dir, "pid")
        self.buckd_stdout = os.path.join(self.buckd_dir, "stdout")
        self.buckd_stderr = os.path.join(self.buckd_dir, "stderr")
        self.buckd_jvm_args_file = os.path.join(self.buckd_dir, "buckjavaargs.running")

        buck_javaargs_path = os.path.join(self.root, ".buckjavaargs")
        self.buck_javaargs = get_file_contents_if_exists(buck_javaargs_path)

        buck_javaargs_path_local = os.path.join(self.root, ".buckjavaargs.local")
        self.buck_javaargs_local = get_file_contents_if_exists(buck_javaargs_path_local)

    # A hash that uniquely identifies this instance of buck.
    # Historically, this has meant 'one buck per repo' or 'one buck per root',
    # but isolation mode means we can have multiple bucks coexisting.
    # Useful for disambiguating identifiers in a global namespace.
    def get_instance_hash(self):
        return hashlib.sha256(
            "{}{}".format(self.root, self.prefix).encode("utf-8")
        ).hexdigest()

    # keep in sync with get_buckd_transport_address
    def get_buckd_transport_file_path(self):
        if os.name == "nt":
            return u"\\\\.\\pipe\\buckd_{0}".format(self.get_instance_hash())
        else:
            return os.path.join(self.buckd_dir, "sock")

    def get_buckd_transport_address(self):
        if os.name == "nt":
            # Nailgun prepends named pipe prefix by itself
            return "local:buckd_{0}".format(self.get_instance_hash())
        else:
            # Nailgun assumes path is relative to self.root
            return "local:{0}.buckd/sock".format(self.prefix)

    def get_running_buckd_version(self):
        return get_file_contents_if_exists(self.buckd_version_file)

    def get_running_buckd_pid(self):
        try:
            return int(get_file_contents_if_exists(self.buckd_pid_file))
        except ValueError:
            return None
        except TypeError:
            return None

    def get_running_buckd_jvm_args(self):
        args_string = get_file_contents_if_exists(self.buckd_jvm_args_file)
        return args_string.split("\n") if args_string is not None else []

    def get_buckd_stdout(self):
        return self.buckd_stdout

    def get_buckd_stderr(self):
        return self.buckd_stderr

    def get_buck_out_log_dir(self):
        return self._buck_out_log

    def get_buck_out_relative_dir(self):
        return self._buck_out_dirname

    def get_section_lock_path(self, section):
        prefix_user_hash = hashlib.sha256(
            (self.prefix + "\n" + USER_NAME).encode("utf8")
        ).hexdigest()
        return os.path.join(
            tempfile.gettempdir(), ".buck_lock_%s_%s" % (section, prefix_user_hash)
        )

    def clean_up_buckd(self):
        with Tracing("BuckProject.clean_up_buckd"):
            if os.path.exists(self.buckd_dir):
                file_locks.rmtree_if_can_lock(self.buckd_dir)

    def create_buckd_dir(self):
        makedirs(self.buckd_dir)

    def create_buckd_tmp_dir(self):
        if self.buckd_tmp_dir is not None:
            return self.buckd_tmp_dir
        self.buckd_tmp_dir = tempfile.mkdtemp(
            prefix="buckd_tmp.", dir=self._buck_out_tmp
        )
        return self.buckd_tmp_dir

    def save_buckd_version(self, version):
        write_contents_to_file(self.buckd_version_file, version)

    def save_buckd_pid(self, pid):
        write_contents_to_file(self.buckd_pid_file, str(pid))

    def save_buckd_jvm_args(self, args):
        write_contents_to_file(self.buckd_jvm_args_file, "\n".join(args))

    @staticmethod
    def from_current_dir():
        with Tracing("BuckProject.from_current_dir"):
            current_dir = os.getcwd()
            if "--version" in sys.argv or "-V" in sys.argv:
                return BuckProject(current_dir)
            at_root_dir = False
            while not at_root_dir:
                if os.path.exists(os.path.join(current_dir, ".buckconfig")):
                    return BuckProject(current_dir)
                parent_dir = os.path.dirname(current_dir)
                at_root_dir = current_dir == parent_dir
                current_dir = parent_dir
            raise NoBuckConfigFoundException()

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        with Tracing("BuckProject.__exit__"):
            if os.path.exists(self.tmp_dir):
                try:
                    shutil.rmtree(self.tmp_dir)
                except OSError as e:
                    if e.errno != errno.ENOENT:
                        raise


class NoBuckConfigFoundException(Exception):
    def __init__(self):
        no_buckconfig_message_path = ".no_buckconfig_message"
        default_message = textwrap.dedent(
            """\
            This does not appear to be the root of a Buck project. Please 'cd'
            to the root of your project before running buck. If this really is
            the root of your project, run
            'touch .buckconfig'
            and then re-run your buck command."""
        )
        message = get_file_contents_if_exists(
            no_buckconfig_message_path, default_message
        )
        Exception.__init__(self, message)
