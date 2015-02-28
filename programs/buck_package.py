import os
import stat
import tempfile

import pkg_resources

from buck_tool import BuckTool, Resource


SERVER = Resource("buck_server")
BOOTSTRAPPER = Resource("bootstrapper_classes")


class BuckPackage(BuckTool):

    def _get_buck_version_uid(self):
        return pkg_resources.resource_string(__name__, 'buck_package_version')

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

    def _get_bootstrap_classpath(self):
        return self._get_resource(BOOTSTRAPPER)

    def _get_java_classpath(self):
        return self._get_resource(SERVER)
