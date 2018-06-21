from __future__ import print_function

import contextlib
import errno
import json
import os
import shutil
import stat
import tempfile

import pkg_resources

import file_locks
from buck_tool import BuckTool, Resource

SERVER = Resource("buck_server")
BOOTSTRAPPER = Resource("bootstrapper_jar")
BUCK_BINARY_HASH = Resource("buck_binary_hash")

PEX_ONLY_EXPORTED_RESOURCES = [Resource("external_executor_jar")]

MODULES_DIR = "buck-modules"
MODULES_RESOURCES_DIR = "buck-modules-resources"


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
    def __init__(self, buck_project, buck_reporter):
        super(BuckPackage, self).__init__(buck_project, buck_reporter)
        self._resource_subdir = None
        self._lock_file = None

    def _get_package_info(self):
        return json.loads(pkg_resources.resource_string(__name__, "buck_package_info"))

    def _get_buck_git_commit(self):
        return self._get_buck_version_uid()

    def _get_resource_dir(self):
        if self._use_buckd:
            base_dir = self._buck_project.buckd_dir
        else:
            base_dir = self._tmp_dir
        return os.path.join(base_dir, "resources")

    def _get_resource_subdir(self):
        def try_subdir(lock_file_dir):
            self.__create_dir(lock_file_dir)
            lock_file_path = os.path.join(lock_file_dir, file_locks.BUCK_LOCK_FILE_NAME)
            lock_file = open(lock_file_path, "a+")
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
                    raise Exception(
                        "Could not acquire lock in fresh tmp dir: " + subdir
                    )
                self._resource_subdir = subdir

        return self._resource_subdir

    def __create_dir(self, dir):
        try:
            os.makedirs(dir)
        except OSError as ex:
            # Multiple threads may try to create this at the same time, so just swallow the
            # error if is about the directory already existing.
            if ex.errno != errno.EEXIST:
                raise

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
                if f == "":
                    # TODO(beng): Figure out why this happens
                    continue
                # TODO: Handle executable resources in directory
                self._unpack_resource(
                    os.path.join(resource_path, f),
                    os.path.join(resource_name, f),
                    False,
                )
        else:
            with closable_named_temporary_file(
                prefix=resource_path + os.extsep
            ) as outf:
                outf.write(pkg_resources.resource_string(__name__, resource_name))
                if resource_executable and hasattr(os, "fchmod"):
                    st = os.fstat(outf.fileno())
                    os.fchmod(outf.fileno(), st.st_mode | stat.S_IXUSR)
                outf.close()
                shutil.copy(outf.name, resource_path)

    def _get_extra_java_args(self):
        modules_dir = os.path.join(self._resource_subdir, MODULES_DIR)
        module_resources_dir = os.path.join(
            self._resource_subdir, "buck-modules-resources"
        )
        return [
            "-Dbuck.git_dirty=0",
            "-Dbuck.path_to_python_dsl=",
            "-Dpf4j.pluginsDir={}".format(modules_dir),
            "-Dbuck.mode=package",
            "-Dbuck.module.resources={}".format(module_resources_dir),
        ]

    def _get_exported_resources(self):
        return (
            super(BuckPackage, self)._get_exported_resources()
            + PEX_ONLY_EXPORTED_RESOURCES
        )

    def _get_bootstrap_classpath(self):
        return self._get_resource(BOOTSTRAPPER)

    def _get_java_classpath(self):
        return self._get_resource(SERVER)

    def _get_buck_binary_hash(self):
        with open(self._get_resource(BUCK_BINARY_HASH), "r") as buck_binary_hash_file:
            return buck_binary_hash_file.read().strip()

    def _unpack_modules(self):
        self._unpack_dir(
            MODULES_DIR, os.path.join(self._get_resource_subdir(), MODULES_DIR)
        )
        self._unpack_dir(
            MODULES_RESOURCES_DIR,
            os.path.join(self._get_resource_subdir(), MODULES_RESOURCES_DIR),
        )

    def _unpack_dir(self, resource_dir, dst_dir):
        if not pkg_resources.resource_exists(__name__, resource_dir):
            raise Exception(
                "Cannot unpack directory: {0} doesn't exist in the package".format(
                    resource_dir
                )
            )

        if not pkg_resources.resource_isdir(__name__, resource_dir):
            raise Exception(
                "Cannot unpack directory: {0} is not a directory".format(resource_dir)
            )

        self.__create_dir(dst_dir)

        if not os.path.exists(dst_dir):
            raise Exception(
                "Cannot unpack directory: cannot create directory {0}".format(dst_dir)
            )

        for resource_file in pkg_resources.resource_listdir(__name__, resource_dir):
            resource_path = os.path.join(dst_dir, resource_file)
            if os.path.exists(resource_path):
                continue
            self._unpack_resource(
                resource_path, "/".join((resource_dir, resource_file)), False
            )

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        if self._lock_file:
            self._lock_file.close()
            self._lock_file = None
