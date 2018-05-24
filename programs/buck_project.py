from __future__ import print_function
import errno
import os
import tempfile
import textwrap
import shutil
import sys

import file_locks
from tracing import Tracing
import hashlib


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


def makedirs(path):
    try:
        os.makedirs(path)
    except OSError as e:
        # Potentially the case that multiple processes are running in parallel
        # (e.g. a series of linters running buck query without buckd), so we
        # should just swallow the error.
        # This is mostly equivalent to os.makedirs(path, exist_ok=True) in
        # Python 3.
        if e.errno != errno.EEXIST and os.path.isdir(path):
            raise


class BuckProject:

    def __init__(self, root):
        self.root = root
        self._buck_out = os.path.join(root, "buck-out")
        buck_out_tmp = os.path.join(self._buck_out, "tmp")
        makedirs(buck_out_tmp)
        self._buck_out_log = os.path.join(self._buck_out, "log")
        makedirs(self._buck_out_log)
        self.tmp_dir = tempfile.mkdtemp(prefix="buck_run.", dir=buck_out_tmp)

        # Only created if buckd is used.
        self.buckd_tmp_dir = None

        self.buckd_dir = os.path.join(root, ".buckd")
        self.buckd_version_file = os.path.join(self.buckd_dir, "buckd.version")
        self.buckd_pid_file = os.path.join(self.buckd_dir, "pid")
        self.buckd_stdout = os.path.join(self.buckd_dir, "stdout")
        self.buckd_stderr = os.path.join(self.buckd_dir, "stderr")

        buck_javaargs_path = os.path.join(self.root, ".buckjavaargs")
        self.buck_javaargs = get_file_contents_if_exists(buck_javaargs_path)

        buck_javaargs_path_local = os.path.join(
            self.root, ".buckjavaargs.local")
        self.buck_javaargs_local = get_file_contents_if_exists(
            buck_javaargs_path_local)

    def get_root_hash(self):
        return hashlib.sha256(self.root).hexdigest()

    def get_buckd_transport_file_path(self):
        if os.name == 'nt':
            return ur'\\.\pipe\buckd_{0}'.format(self.get_root_hash())
        else:
            return os.path.join(self.buckd_dir, 'sock')

    def get_buckd_transport_address(self):
        if os.name == 'nt':
            return 'local:buckd_{0}'.format(self.get_root_hash())
        else:
            return 'local:.buckd/sock'

    def get_running_buckd_version(self):
        return get_file_contents_if_exists(self.buckd_version_file)

    def get_running_buckd_pid(self):
        try:
            return int(get_file_contents_if_exists(self.buckd_pid_file))
        except ValueError:
            return None
        except TypeError:
            return None

    def get_buckd_stdout(self):
        return self.buckd_stdout

    def get_buckd_stderr(self):
        return self.buckd_stderr

    def get_buck_out_log_dir(self):
        return self._buck_out_log

    def clean_up_buckd(self):
        with Tracing('BuckProject.clean_up_buckd'):
            if os.path.exists(self.buckd_dir):
                file_locks.rmtree_if_can_lock(self.buckd_dir)

    def create_buckd_tmp_dir(self):
        if self.buckd_tmp_dir is not None:
            return self.buckd_tmp_dir
        tmp_dir_parent = os.path.join(self.buckd_dir, "tmp")
        makedirs(tmp_dir_parent)
        self.buckd_tmp_dir = tempfile.mkdtemp(prefix="buck_run.",
                                              dir=tmp_dir_parent)
        return self.buckd_tmp_dir

    def save_buckd_version(self, version):
        write_contents_to_file(self.buckd_version_file, version)

    def save_buckd_pid(self, pid):
        write_contents_to_file(self.buckd_pid_file, str(pid))

    @staticmethod
    def from_current_dir():
        with Tracing('BuckProject.from_current_dir'):
            current_dir = os.getcwd()
            if '--version' in sys.argv or '-V' in sys.argv:
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
        with Tracing('BuckProject.__exit__'):
            if os.path.exists(self.tmp_dir):
                try:
                    shutil.rmtree(self.tmp_dir)
                except OSError as e:
                    if e.errno != errno.ENOENT:
                        raise


class NoBuckConfigFoundException(Exception):

    def __init__(self):
        no_buckconfig_message_path = ".no_buckconfig_message"
        default_message = textwrap.dedent("""\
            This does not appear to be the root of a Buck project. Please 'cd'
            to the root of your project before running buck. If this really is
            the root of your project, run
            'touch .buckconfig'
            and then re-run your buck command.""")
        message = get_file_contents_if_exists(no_buckconfig_message_path, default_message)
        Exception.__init__(self, message)
