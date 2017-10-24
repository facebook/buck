from __future__ import print_function
import logging
import os
import os.path
import sys

from tracing import Tracing
from buck_tool import BuckTool, platform_path
from subprocutils import which
import buck_version

# If you're looking for JAVA_CLASSPATHS, they're now defined in the programs/classpaths file.

RESOURCES = {
    "android_agent_path": "assets/android/agent.apk",
    "buck_server": "bin/buck",
    "buck_build_type_info": "config/build_type/LOCAL_ANT/type.txt",
    "dx": "third-party/java/dx/etc/dx",
    "jacoco_agent_jar": "third-party/java/jacoco/jacocoagent.jar",
    "libjcocoa.dylib": "third-party/java/ObjCBridge/libjcocoa.dylib",
    "logging_config_file": "config/logging.properties.st",
    "native_exopackage_fake_path": "assets/android/native-exopackage-fakes.apk",
    "path_to_rawmanifest_py": "src/com/facebook/buck/util/versioncontrol/rawmanifest.py",
    "path_to_pex": "src/com/facebook/buck/python/make_pex.py",
    "path_to_sh_binary_template": "src/com/facebook/buck/shell/sh_binary_template",
    "report_generator_jar": "build/report-generator.jar",
    "testrunner_classes": "build/testrunner/classes",

    # python resources used by buck file parser.
    "path_to_pathlib_py": "third-party/py/pathlib/pathlib.py",
    "path_to_pywatchman": "third-party/py/pywatchman",
    "path_to_typing": "third-party/py/typing/python2",
    "path_to_python_dsl": "python-dsl",
}

BUCK_BINARY_HASH_LOCATION = os.path.join("build", "classes", "META-INF", "buck-binary-hash.txt")


class BuckRepo(BuckTool):

    def __init__(self, buck_bin_dir, buck_project):
        super(BuckRepo, self).__init__(buck_project)

        self.buck_dir = platform_path(os.path.dirname(buck_bin_dir))

        dot_git = os.path.join(self.buck_dir, '.git')
        self.is_git = os.path.exists(dot_git) and os.path.isdir(dot_git) and which('git') and \
            sys.platform != 'cygwin'
        self._is_buck_repo_dirty_override = os.environ.get('BUCK_REPOSITORY_DIRTY')
        if not self._fake_buck_version:
            # self._fake_buck_version has been set previously through BuckTool when the environment
            # variable BUCK_FAKE_VERSION is set.
            # If the environement variable is not set, we'll use the content of .fakebuckversion
            # at the root of the repository if it exists.
            fake_buck_version_file_path = os.path.join(self.buck_dir, ".fakebuckversion")
            if os.path.exists(fake_buck_version_file_path):
                with open(fake_buck_version_file_path) as fake_buck_version_file:
                    self._fake_buck_version = fake_buck_version_file.read().strip()
                    logging.info("Using fake buck version (via .fakebuckversion): {}".format(
                        self._fake_buck_version))

    def _join_buck_dir(self, relative_path):
        return os.path.join(self.buck_dir, *(relative_path.split('/')))

    def get_git_revision(self):
        if not self.is_git:
            return 'N/A'
        return buck_version.get_git_revision(self.buck_dir)

    def _get_git_commit_timestamp(self):
        if self._is_buck_repo_dirty_override or not self.is_git:
            return -1
        return buck_version.get_git_revision_timestamp(self.buck_dir)

    def _get_resource_lock_path(self):
        return None

    def _has_resource(self, resource):
        return True

    def _get_resource(self, resource, exe=False):
        return self._join_buck_dir(RESOURCES[resource.name])

    def _get_buck_version_timestamp(self):
        return self._get_git_commit_timestamp()

    def _get_buck_version_uid(self):
        with Tracing('BuckRepo._get_buck_version_uid'):
            if self._fake_buck_version:
                return self._fake_buck_version

            with open(os.path.join(self.buck_dir,
                                   BUCK_BINARY_HASH_LOCATION)) as buck_binary_hash_file:
                return buck_binary_hash_file.read().strip()

    def _get_buck_git_commit(self):
        with Tracing('BuckRepo._get_buck_git_commit'):
            # First try to get the "clean" buck version.  If it succeeds,
            # return it.
            return buck_version.get_git_revision(self.buck_dir)

    def _get_buck_repo_dirty(self):
        raise buck_version.is_dirty(self.buck_dir)

    def _get_extra_java_args(self):
        with Tracing('BuckRepo._get_extra_java_args'):
            return [
                "-Dbuck.git_commit={0}".format(self._get_buck_version_uid()),
                "-Dbuck.git_commit_timestamp={0}".format(
                    self._get_git_commit_timestamp()),
                "-Dbuck.git_dirty={0}".format(
                  int(self._is_buck_repo_dirty_override == "1" or
                      buck_version.is_dirty(self.buck_dir))),
            ]

    def _get_bootstrap_classpath(self):
        return self._join_buck_dir("build/bootstrapper/bootstrapper.jar")

    def _get_java_classpath(self):
        classpath_file_path = os.path.join(self.buck_dir, "build", "classpath", "classpaths")
        classpath_entries = []
        with open(classpath_file_path, 'r') as classpath_file:
            for line in classpath_file.readlines():
                line = line.strip()
                if line.startswith('#'):
                    continue
                classpath_entries.append(line)
        return self._pathsep.join([self._join_buck_dir(p) for p in classpath_entries])


    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        pass
