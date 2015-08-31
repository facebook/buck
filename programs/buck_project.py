from __future__ import print_function
import os
import subprocess
import tempfile
import textwrap
import shutil
import sys

from tracing import Tracing


def get_file_contents_if_exists(path, default=None):
    with Tracing('BuckProject.get_file_contents_if_it_exists', args={'path': path}):
        if not os.path.exists(path):
            return default
        with open(path) as f:
            contents = f.read().strip()
            return default if not contents else contents


def write_contents_to_file(path, contents):
    with Tracing('BuckProject.write_contents_to_file', args={'path': path}):
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
        self.autobuild_pid_file = os.path.join(self.buckd_dir, "autobuild.pid")
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

    def get_buckd_socket_path(self):
        return os.path.join(self.buckd_dir, 'sock')

    def get_running_buckd_version(self):
        return get_file_contents_if_exists(self.buckd_version_file)

    def get_autobuild_pid(self):
        return get_file_contents_if_exists(self.autobuild_pid_file)

    def get_buck_out_log_dir(self):
        return self._buck_out_log

    def update_buckd_run_count(self, new_run_count):
        write_contents_to_file(self.buckd_run_count_file, new_run_count)

    def clean_up_buckd(self):
        with Tracing('BuckProject.clean_up_buckd'):
            if os.path.exists(self.buckd_dir):
                shutil.rmtree(self.buckd_dir)

    def create_buckd_tmp_dir(self):
        if self.buckd_tmp_dir is not None:
            return self.buckd_tmp_dir
        tmp_dir_parent = os.path.join(self.buckd_dir, "tmp")
        if not os.path.exists(tmp_dir_parent):
            os.makedirs(tmp_dir_parent)
        self.buckd_tmp_dir = tempfile.mkdtemp(prefix="buck_run.",
                                              dir=tmp_dir_parent)
        return self.buckd_tmp_dir

    def save_buckd_version(self, version):
        write_contents_to_file(self.buckd_version_file, version)

    @staticmethod
    def from_current_dir():
        with Tracing('BuckProject.from_current_dir'):
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
        with Tracing('BuckProject.__exit__'):
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
