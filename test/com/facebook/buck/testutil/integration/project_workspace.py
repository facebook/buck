# Copyright 2016-present Facebook, Inc.
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

import os
import platform
import shutil
import subprocess
import sys
import tempfile


def run_buck_process(command, cwd=None):
    root_directory = os.getcwd()
    cwd = cwd or root_directory
    buck_path = os.path.join(root_directory, "buck-out", "gen", "programs", "buck.pex")
    if platform.system() == "Windows":
        args = ["python", buck_path] + list(command)
    else:
        args = [buck_path] + list(command)
    # Pass thru our environment, except disabling buckd so that we can be sure the right buck
    # is run.
    child_environment = dict(os.environ)
    child_environment["NO_BUCKD"] = "1"

    return subprocess.Popen(
        args,
        cwd=cwd,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=child_environment,
    )


class ProjectWorkspace(object):

    is_windows = platform.system() == "Windows"

    def __init__(self, template_data_directory):
        self._template_data_directory = template_data_directory
        self._temp_dir = tempfile.mkdtemp()
        self.test_data_directory = os.path.join(self._temp_dir, "test")

    def __enter__(self):
        shutil.copytree(self._template_data_directory, self.test_data_directory)
        for root, dirs, files in os.walk(self.test_data_directory):
            for f in files:
                filename, fileext = os.path.splitext(f)
                if fileext == ".fixture":
                    os.rename(os.path.join(root, f), os.path.join(root, filename))
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        if self.is_windows:
            # We do this due to bug: http://bugs.python.org/issue22022
            subprocess.call(["rd", "/S", "/Q", self._temp_dir], shell=True)
        else:
            shutil.rmtree(self._temp_dir)

    def resolve_path(self, path):
        return os.path.join(self.test_data_directory, path)

    def run_buck(self, *command):
        proc = run_buck_process(command, self.test_data_directory)
        stdout, stderr = proc.communicate()

        # Copy output through to unittest's output so failures are easy to debug. Can't just
        # provide sys.stdout/sys.stderr to Popen because unittest has replaced the streams with
        # things that aren't directly compatible with Popen.
        sys.stdout.write(stdout)
        sys.stdout.flush()
        sys.stderr.write(stderr)
        sys.stderr.flush()

        return proc.returncode
