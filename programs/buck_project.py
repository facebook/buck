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
    except OSError, e:
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

        self.has_no_buck_check = (os.path.exists(os.path.join(
            self.root, ".nobuckcheck")))

        if self.has_no_buck_check:
            print(textwrap.dedent(
                """::: '.nobuckcheck' file is present. Not updating buck."""),
                file=sys.stderr)

        buck_version_path = os.path.join(self.root, ".buckversion")
        buck_version = get_file_contents_if_exists(buck_version_path)
        self.buck_version = buck_version.split(':') if buck_version else None

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
