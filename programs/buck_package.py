import os
import json
import stat
import tempfile

import pkg_resources

from buck_tool import BuckTool, Resource


SERVER = Resource("buck_server")
BOOTSTRAPPER = Resource("bootstrapper_jar")


class BuckPackage(BuckTool):

    def __init__(self, buck_project):
        super(BuckPackage, self).__init__(buck_project)
        self._package_info = json.loads(
            pkg_resources.resource_string(__name__, 'buck_package_info'))

    def _get_buck_version_uid(self):
        return self._package_info['version']

    def _get_resource_dir(self):
        if self._use_buckd():
            tmp_dir = self._buck_project.create_buckd_tmp_dir()
        else:
            tmp_dir = self._tmp_dir
        return os.path.join(tmp_dir, 'resources')

    def _has_resource(self, resource):
        return pkg_resources.resource_exists(__name__, resource.name)

    def _get_resource(self, resource):
        buck_version_uid = self._get_buck_version_uid()
        resource_path = os.path.join(
            self._get_resource_dir(),
            buck_version_uid,
            resource.basename)
        if not os.path.exists(resource_path):
            if not os.path.exists(os.path.dirname(resource_path)):
                os.makedirs(os.path.dirname(resource_path))
            with tempfile.NamedTemporaryFile(prefix=resource_path + os.extsep) as outf:
                outf.write(pkg_resources.resource_string(__name__, resource.name))
                if resource.executable:
                    st = os.fstat(outf.fileno())
                    os.fchmod(outf.fileno(), st.st_mode | stat.S_IXUSR)
                os.rename(outf.name, resource_path)
                outf.delete = False
        return resource_path

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

    def _get_pywatchman_path(self):
        # TODO(agallagher): Update this to return the path to the pywatchman resource directory.
        return None
