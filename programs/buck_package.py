import errno
import contextlib
import os
import json
import shutil
import stat
import sys
import tempfile

import pkg_resources
import file_locks

from buck_tool import BuckTool, Resource


SERVER = Resource("buck_server")
BOOTSTRAPPER = Resource("bootstrapper_jar")


@contextlib.contextmanager
def closable_named_temporary_file(*args, **kwargs):
    """
    Due to a bug in python (https://bugs.python.org/issue14243), we need to be able to close() the
    temporary file without deleting it.
    """
    fp = tempfile.NamedTemporaryFile(*args, delete=False, **kwargs)
    try:
        with fp:
            yield fp
    finally:
        try:
            os.remove(fp.name)
        except OSError as e:
            # It's possible this fails because of a race with another buck
            # instance has removed the entire resource_path, so ignore
            # 'file not found' errors.
            if e.errno != errno.ENOENT:
                raise


class BuckPackage(BuckTool):

    def __init__(self, buck_project):
        super(BuckPackage, self).__init__(buck_project)
        self._package_info = json.loads(
            pkg_resources.resource_string(__name__, 'buck_package_info'))
        self._resource_subdir = None
        self._lock_file = None

    def _get_buck_version_uid(self):
        return self._package_info['version']

    def _get_resource_dir(self):
        if self._use_buckd:
            base_dir = self._buck_project.buckd_dir
        else:
            base_dir = self._tmp_dir
        return os.path.join(base_dir, 'resources')

    def _get_resource_subdir(self):
        def try_subdir(lock_file_dir):
            try:
                os.makedirs(lock_file_dir)
            except OSError as ex:
                # Multiple threads may try to create this at the same time, so just swallow the
                # error if is about the directory already existing.
                if ex.errno != errno.EEXIST:
                    raise
            lock_file_path = os.path.join(lock_file_dir, file_locks.BUCK_LOCK_FILE_NAME)
            lock_file = open(lock_file_path, 'a+')
            if file_locks.acquire_shared_lock(lock_file):
                return lock_file
            else:
                return None

        if self._resource_subdir is None:
            buck_version_uid = self._get_buck_version_uid()
            resource_dir = self._get_resource_dir()
            subdir = os.path.join(resource_dir, buck_version_uid)
            self._lock_file = try_subdir(subdir)
            if self._lock_file:
                self._resource_subdir = subdir
            else:
                subdir = tempfile.mkdtemp(dir=resource_dir, prefix=buck_version_uid)
                self._lock_file = try_subdir(subdir)
                if not self._lock_file:
                    raise Exception('Could not acquire lock in fresh tmp dir: ' + subdir)
                self._resource_subdir = subdir

        return self._resource_subdir

    def _get_resource_lock_path(self):
        return os.path.join(self._get_resource_subdir(), file_locks.BUCK_LOCK_FILE_NAME)

    def _has_resource(self, resource):
        return pkg_resources.resource_exists(__name__, resource.name)

    def _get_resource(self, resource):
        resource_path = os.path.join(self._get_resource_subdir(), resource.basename)
        if not os.path.exists(os.path.dirname(resource_path)):
            os.makedirs(os.path.dirname(resource_path))
        if not os.path.exists(resource_path):
            self._unpack_resource(resource_path, resource.name, resource.executable)
        return resource_path

    def _unpack_resource(self, resource_path, resource_name, resource_executable):
        if not pkg_resources.resource_exists(__name__, resource_name):
            return

        if pkg_resources.resource_isdir(__name__, resource_name):
            os.mkdir(resource_path)
            for f in pkg_resources.resource_listdir(__name__, resource_name):
                if f == '':
                    # TODO(beng): Figure out why this happens
                    continue
                # TODO: Handle executable resources in directory
                self._unpack_resource(
                    os.path.join(resource_path, f),
                    os.path.join(resource_name, f),
                    False)
        else:
            with closable_named_temporary_file(prefix=resource_path + os.extsep) as outf:
                outf.write(pkg_resources.resource_string(__name__, resource_name))
                if resource_executable and hasattr(os, 'fchmod'):
                    st = os.fstat(outf.fileno())
                    os.fchmod(outf.fileno(), st.st_mode | stat.S_IXUSR)
                outf.close()
                shutil.copy(outf.name, resource_path)

    def _is_buck_production(self):
        build_type = pkg_resources.resource_string(__name__, 'buck_build_type_info').strip()
        return build_type == 'RELEASE_PEX'

    def _get_extra_java_args(self):
        return [
            "-Dbuck.git_commit={0}".format(self._package_info['version']),
            "-Dbuck.git_commit_timestamp={0}".format(self._package_info['timestamp']),
            "-Dbuck.git_dirty=0",
        ]

    def _get_bootstrap_classpath(self):
        return self._get_resource(BOOTSTRAPPER)

    def _get_java_classpath(self):
        return self._get_resource(SERVER)

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        if self._lock_file:
            self._lock_file.close()
            self._lock_file = None
