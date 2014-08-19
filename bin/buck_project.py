from __future__ import print_function
import json
import os
import subprocess
import tempfile
import textwrap
import shutil
import sys

from buck_repo import check_output, which


def get_file_contents_if_exists(path, default=None):
    if not os.path.exists(path):
        return default
    with open(path) as f:
        return f.read().strip()


def write_contents_to_file(path, contents):
    with open(path, 'w') as output_file:
        output_file.write(str(contents))


class BuckProject:

    def __init__(self, root):
        self.root = root
        self._buck_out = os.path.join(root, "buck-out")
        buck_out_tmp = os.path.join(self._buck_out, "tmp")
        if not os.path.exists(buck_out_tmp):
            os.makedirs(buck_out_tmp)
        self._buck_out_log = os.path.join(self._buck_out, "log")
        if not os.path.exists(self._buck_out_log):
            os.makedirs(self._buck_out_log)
        self.tmp_dir = tempfile.mkdtemp(prefix="buck_run.", dir=buck_out_tmp)

        # Only created if buckd is used.
        self.buckd_tmp_dir = None

        self.buckd_dir = os.path.join(root, ".buckd")
        self.buckd_pid_file = os.path.join(self.buckd_dir, "buckd.pid")
        self.autobuild_pid_file = os.path.join(self.buckd_dir, "autobuild.pid")
        self.buckd_port_file = os.path.join(self.buckd_dir, "buckd.port")
        self.buckd_run_count_file = (os.path.join(
            self.buckd_dir, "buckd.runcount"))
        self.buckd_version_file = os.path.join(self.buckd_dir, "buckd.version")

        self.has_no_buck_check = (os.path.exists(os.path.join(
            self.root, ".nobuckcheck")))

        if self.has_no_buck_check:
            print(textwrap.dedent("""\
            :::
            ::: '.nobuckcheck' file is present. Not updating buck.
            :::"""), file=sys.stderr)

        buck_version_path = os.path.join(self.root, ".buckversion")
        buck_version = get_file_contents_if_exists(buck_version_path)
        self.buck_version = buck_version.split(':') if buck_version else None

        buck_javaargs_path = os.path.join(self.root, ".buckjavaargs")
        self.buck_javaargs = get_file_contents_if_exists(buck_javaargs_path)

    def get_buckd_run_count(self):
        return int(get_file_contents_if_exists(self.buckd_run_count_file, -1))

    def get_running_buckd_version(self):
        return get_file_contents_if_exists(self.buckd_version_file)

    def get_buckd_pid(self):
        return get_file_contents_if_exists(self.buckd_pid_file)

    def get_autobuild_pid(self):
        return get_file_contents_if_exists(self.autobuild_pid_file)

    def get_buckd_port(self):
        return get_file_contents_if_exists(self.buckd_port_file)

    def get_buck_out_log_dir(self):
        return self._buck_out_log

    def update_buckd_run_count(self, new_run_count):
        write_contents_to_file(self.buckd_run_count_file, new_run_count)

    def clean_up_buckd(self):
        if os.path.exists(self.buckd_dir):
            shutil.rmtree(self.buckd_dir)
        if which('watchman'):
            trigger_list_output = check_output(
                ['watchman', 'trigger-list', self.root])
            trigger_list = json.loads(trigger_list_output)
            if not trigger_list.get('triggers'):
                subprocess.call(
                    ['watchman', 'watch-del', self.root],
                    stdout=open(os.devnull, 'w'))

    def create_buckd_tmp_dir(self):
        tmp_dir_parent = os.path.join(self.buckd_dir, "tmp")
        if not os.path.exists(tmp_dir_parent):
            os.makedirs(tmp_dir_parent)
        self.buckd_tmp_dir = tempfile.mkdtemp(prefix="buck_run.",
                                              dir=tmp_dir_parent)

    def save_buckd_pid(self, pid):
        write_contents_to_file(self.buckd_pid_file, pid)

    def save_buckd_port(self, port):
        write_contents_to_file(self.buckd_port_file, port)

    def save_buckd_version(self, version):
        write_contents_to_file(self.buckd_version_file, version)

    @staticmethod
    def from_current_dir():
        current_dir = os.getcwd()
        if '--version' in sys.argv or '-V' in sys.argv:
            return BuckProject(current_dir)
        while current_dir != os.sep:
            if os.path.exists(os.path.join(current_dir, ".buckconfig")):
                return BuckProject(current_dir)
            current_dir = os.path.dirname(current_dir)
        raise NoBuckConfigFoundException()

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        if os.path.exists(self.tmp_dir):
            shutil.rmtree(self.tmp_dir)


class NoBuckConfigFoundException(Exception):

    def __init__(self):
        message = textwrap.dedent("""\
            This does not appear to be the root of a Buck project. Please 'cd'
            to the root of your project before running buck. If this really is
            the root of your project, run
            'touch .buckconfig'
            and then re-run your buck command.""")
        Exception.__init__(self, message)
