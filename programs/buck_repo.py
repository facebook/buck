from __future__ import print_function
import os
import subprocess
import sys
import textwrap

from tracing import Tracing
from buck_tool import BuckTool, JAVA_MAX_HEAP_SIZE_MB, platform_path
from buck_tool import BuckToolException, RestartBuck
from subprocess import check_output
from subprocutils import which
import buck_version

JAVA_CLASSPATHS = [
    "build/abi_processor/classes",
    "build/classes",
    "build/src-gen/classes",
    "build/aosp/classes",
    "build/dx_classes",
    "src",
    "src-gen",
    "third-party/java/android/common-25.2.0.jar",
    "third-party/java/android/layoutlib-api-25.2.0.jar",
    "third-party/java/android/sdk-common-25.2.0.jar",
    "third-party/java/android/sdklib-25.2.0.jar",
    "third-party/java/aopalliance/aopalliance.jar",
    "third-party/java/args4j/args4j-2.0.30.jar",
    "third-party/java/asm/asm-debug-all-5.0.3.jar",
    "third-party/java/closure-templates/soy-excluding-deps.jar",
    "third-party/java/commons-compress/commons-compress-1.8.1.jar",
    "third-party/java/commons-logging/commons-logging-1.2.jar",
    "third-party/java/concurrent-locks/concurrent-locks-1.0.0.jar",
    "third-party/java/dd-plist/dd-plist.jar",
    "third-party/java/ddmlib/ddmlib-25.2.0.jar",
    "third-party/java/diffutils/diffutils-1.3.0.jar",
    "third-party/java/eclipse-collections/eclipse-collections-7.1.1.jar",
    "third-party/java/eclipse-collections/eclipse-collections-api-7.1.1.jar",
    "third-party/java/eclipse-collections/eclipse-collections-forkjoin-7.1.1.jar",
    "third-party/java/eclipse/org.eclipse.core.contenttype_3.5.100.v20160418-1621.jar",
    "third-party/java/eclipse/org.eclipse.core.jobs_3.8.0.v20160319-0610.jar",
    "third-party/java/eclipse/org.eclipse.core.resources_3.11.0.v20160422-0304.jar",
    "third-party/java/eclipse/org.eclipse.core.runtime_3.12.0.v20160427-1901.jar",
    "third-party/java/eclipse/org.eclipse.equinox.common_3.8.0.v20160422-1942.jar",
    "third-party/java/eclipse/org.eclipse.equinox.preferences_3.6.0.v20160120-1756.jar",
    "third-party/java/eclipse/org.eclipse.jdt.core.prefs",
    "third-party/java/eclipse/org.eclipse.jdt.core_3.12.0.v20160426-1326.jar",
    "third-party/java/eclipse/org.eclipse.osgi_3.11.0.v20160427-2120.jar",
    "third-party/java/errorprone/error-prone-annotations-2.0.15.jar",
    "third-party/java/gson/gson-2.2.4.jar",
    "third-party/java/guava/guava-20.0.jar",
    "third-party/java/guice/guice-3.0.jar",
    "third-party/java/guice/guice-assistedinject-3.0.jar",
    "third-party/java/guice/guice-multibindings-3.0.jar",
    "third-party/java/httpcomponents/httpclient-4.4.1.jar",
    "third-party/java/httpcomponents/httpcore-4.4.1.jar",
    "third-party/java/icu4j/icu4j-54.1.1.jar",
    "third-party/java/infer-annotations/infer-annotations-4.1.jar",
    "third-party/java/ini4j/ini4j-0.5.2.jar",
    "third-party/java/jackson/jackson-annotations-2.7.8.jar",
    "third-party/java/jackson/jackson-core-2.7.8.jar",
    "third-party/java/jackson/jackson-databind-2.7.8.jar",
    "third-party/java/jackson/jackson-datatype-guava-2.7.8.jar",
    "third-party/java/jackson/jackson-datatype-jdk8-2.7.8.jar",
    "third-party/java/jcip-annotations/jcip-annotations-1.0.jar",
    "third-party/java/jdom/jdom-2.0.6.jar",
    "third-party/java/jetty/jetty-all-9.2.10.v20150310.jar",
    "third-party/java/jna/jna-4.2.0.jar",
    "third-party/java/jna/jna-platform-4.2.0.jar",
    "third-party/java/jsr/javax.inject-1.jar",
    "third-party/java/jsr/jsr305.jar",
    "third-party/java/kotlin/kotlin-runtime-1.0.6.jar",
    "third-party/java/kotlin/kotlin-stdlib-1.0.6.jar",
    "third-party/java/kxml2/kxml2-2.3.0.jar",
    "third-party/java/lz4/lz4-1.3.0.jar",
    "third-party/java/mapdb/elsa-3.0.0-M5.jar",
    "third-party/java/mapdb/mapdb-3.0.3.jar",
    "third-party/java/nailgun/nailgun-server-0.9.2-SNAPSHOT.jar",
    "third-party/java/nuprocess/nuprocess-1.1.0.jar",
    "third-party/java/ObjCBridge/ObjCBridge.jar",
    "third-party/java/okhttp/okhttp-3.6.0.jar",
    "third-party/java/okio/okio-1.11.0.jar",
    "third-party/java/oshi/oshi-core-3.3-SNAPSHOT.jar",
    "third-party/java/rocksdbjni/rocksdbjni-5.1.2.jar",
    "third-party/java/servlet-api/javax.servlet-api-3.1.0.jar",
    "third-party/java/slf4j/slf4j-jdk14-1.7.5.jar",
    "third-party/java/sqlite/sqlite-jdbc-3.16.1.jar",
    "third-party/java/stringtemplate/ST-4.0.8.jar",
    "third-party/java/thrift/libthrift-0.9.3.jar",
    "third-party/java/xz-java-1.5/xz-1.5.jar",
    # maven/aether libs
    "third-party/java/aether/aether-api-1.0.2.v20150114.jar",
    "third-party/java/aether/aether-connector-basic-1.0.2.v20150114.jar",
    "third-party/java/aether/aether-impl-1.0.0.v20140518.jar",
    "third-party/java/aether/aether-spi-1.0.2.v20150114.jar",
    "third-party/java/aether/aether-transport-http-1.0.2.v20150114.jar",
    "third-party/java/aether/aether-transport-file-1.0.2.v20150114.jar",
    "third-party/java/aether/aether-util-1.0.2.v20150114.jar",
    "third-party/java/commons-codec/commons-codec-1.6.jar",
    "third-party/java/maven/maven-aether-provider-3.2.5.jar",
    "third-party/java/maven/maven-model-3.2.5.jar",
    "third-party/java/maven/maven-model-builder-3.2.5.jar",
    "third-party/java/slf4j/slf4j-api-1.7.5.jar",
    "third-party/java/plexus/plexus-utils-3.0.20.jar",
    "third-party/java/plexus/plexus-interpolation-1.21.jar",
    "third-party/java/eden/eden.jar",
    "third-party/java/eden/java-thrift-dependencies.jar",
]

RESOURCES = {
    "abi_processor_classes": "build/abi_processor/classes",
    "android_agent_path": "assets/android/agent.apk",
    "buck_server": "bin/buck",
    "buck_build_type_info": "config/build_type/LOCAL_ANT/type.txt",
    "dx": "third-party/java/dx/etc/dx",
    "jacoco_agent_jar": "third-party/java/jacoco/jacocoagent.jar",
    "libjcocoa.dylib": "third-party/java/ObjCBridge/libjcocoa.dylib",
    "logging_config_file": "config/logging.properties.st",
    "native_exopackage_fake_path": "assets/android/native-exopackage-fakes.apk",
    "path_to_asm_jar": "third-party/java/asm/asm-debug-all-5.0.3.jar",
    "path_to_rawmanifest_py": "src/com/facebook/buck/util/versioncontrol/rawmanifest.py",
    "path_to_intellij_py": "src/com/facebook/buck/ide/intellij/deprecated/intellij.py",
    "path_to_pex": "src/com/facebook/buck/python/make_pex.py",
    "path_to_sh_binary_template": "src/com/facebook/buck/shell/sh_binary_template",
    "path_to_static_content": "webserver/static",
    "report_generator_jar": "build/report-generator.jar",
    "testrunner_classes": "build/testrunner/classes",

    # python resources used by buck file parser.
    "path_to_pathlib_py": "third-party/py/pathlib/pathlib.py",
    "path_to_pywatchman": "third-party/py/pywatchman",
    "path_to_typing": "third-party/py/typing/python2",
}


def get_ant_env(max_heap_size_mb):
    ant_env = os.environ.copy()
    ant_opts = ant_env.get('ANT_OPTS', '')
    if ant_opts.find('-Xmx') == -1:
        # Adjust the max heap size if it's not already specified.
        ant_max_heap_arg = '-Xmx{0}m'.format(max_heap_size_mb)
        if ant_opts:
            ant_opts += ' '
        ant_opts += ant_max_heap_arg
        ant_env['ANT_OPTS'] = ant_opts
    return ant_env


class BuckRepo(BuckTool):

    def __init__(self, buck_bin_dir, buck_project):
        super(BuckRepo, self).__init__(buck_project)

        self._buck_dir = platform_path(os.path.dirname(buck_bin_dir))
        self._build_success_file = os.path.join(
            self._buck_dir, "build", "successful-build")

        dot_git = os.path.join(self._buck_dir, '.git')
        self._is_git = os.path.exists(dot_git) and os.path.isdir(dot_git) and which('git') and \
            sys.platform != 'cygwin'
        self._is_buck_repo_dirty_override = os.environ.get('BUCK_REPOSITORY_DIRTY')

        buck_version = buck_project.buck_version
        if self._is_git and not buck_project.has_no_buck_check and buck_version:
            revision = buck_version[0]
            branch = buck_version[1] if len(buck_version) > 1 else None
            self._checkout_and_clean(revision, branch)

        self._build()

    def _checkout_and_clean(self, revision, branch):
        with Tracing('BuckRepo._checkout_and_clean'):
            if not self._revision_exists(revision):
                print(textwrap.dedent("""\
                    Required revision {0} is not
                    available in the local repository.
                    Buck is fetching updates from git. You can disable this by creating
                    a '.nobuckcheck' file in your repository, but this might lead to
                    strange bugs or build failures.""".format(revision)),
                      file=sys.stderr)
                git_command = ['git', 'fetch']
                git_command.extend(['--all'] if not branch else ['origin', branch])
                try:
                    subprocess.check_call(
                        git_command,
                        stdout=sys.stderr,
                        cwd=self._buck_dir)
                except subprocess.CalledProcessError:
                    raise BuckToolException(textwrap.dedent("""\
                          Failed to fetch Buck updates from git."""))

            current_revision = self._get_git_revision()

            if current_revision != revision:
                print(textwrap.dedent("""\
                    Buck is at {0}, but should be {1}.
                    Buck is updating itself. To disable this, add a '.nobuckcheck'
                    file to your project root. In general, you should only disable
                    this if you are developing Buck.""".format(
                    current_revision, revision)),
                    file=sys.stderr)

                try:
                    subprocess.check_call(
                        ['git', 'checkout', '--quiet', revision],
                        cwd=self._buck_dir)
                except subprocess.CalledProcessError:
                    raise BuckToolException(textwrap.dedent("""\
                          Failed to update Buck to revision {0}.""".format(revision)))
                if os.path.exists(self._build_success_file):
                    os.remove(self._build_success_file)

                ant = self._check_for_ant()
                self._run_ant_clean(ant)
                raise RestartBuck()

    def _join_buck_dir(self, relative_path):
        return os.path.join(self._buck_dir, *(relative_path.split('/')))

    def _has_local_changes(self):
        if not self._is_git:
            return False

        output = check_output(
            ['git', 'ls-files', '-m'],
            cwd=self._buck_dir)
        return bool(output.strip())

    def _get_git_revision(self):
        if not self._is_git:
            return 'N/A'
        return buck_version.get_git_revision(self._buck_dir)

    def _get_git_commit_timestamp(self):
        if self._is_buck_repo_dirty_override or not self._is_git:
            return -1
        return buck_version.get_git_revision_timestamp(self._buck_dir)

    def _revision_exists(self, revision):
        returncode = subprocess.call(
            ['git', 'cat-file', '-e', revision],
            cwd=self._buck_dir)
        return returncode == 0

    def _check_for_ant(self):
        ant = which('ant')
        if not ant:
            message = "You do not have ant on your $PATH. Cannot build Buck."
            if sys.platform == "darwin":
                message += "\nTry running 'brew install ant'."
            raise BuckToolException(message)
        return ant

    def _print_ant_failure_and_exit(self, ant_log_path):
        print(textwrap.dedent("""\
                ::: 'ant' failed in the buck repo at '{0}',
                ::: and 'buck' is not properly built. It will be unusable
                ::: until the error is corrected. You can check the logs
                ::: at {1} to figure out what broke.""".format(
              self._buck_dir, ant_log_path)), file=sys.stderr)
        if self._is_git:
            raise BuckToolException(textwrap.dedent("""\
                ::: It is possible that running this command will fix it:
                ::: git -C "{0}" clean -xfd""".format(self._buck_dir)))
        else:
            raise BuckToolException(textwrap.dedent("""\
                ::: It is possible that running this command will fix it:
                ::: rm -rf "{0}"/build""".format(self._buck_dir)))

    def _run_ant_clean(self, ant):
        clean_log_path = os.path.join(self._buck_project.get_buck_out_log_dir(), 'ant-clean.log')
        with open(clean_log_path, 'w') as clean_log:
            exitcode = subprocess.call([ant, 'clean'], stdout=clean_log,
                                       cwd=self._buck_dir, env=get_ant_env(JAVA_MAX_HEAP_SIZE_MB))
            if exitcode is not 0:
                self._print_ant_failure_and_exit(clean_log_path)

    def _run_ant(self, ant):
        ant_log_path = os.path.join(self._buck_project.get_buck_out_log_dir(), 'ant.log')
        with open(ant_log_path, 'w') as ant_log:
            exitcode = subprocess.call([ant], stdout=ant_log,
                                       cwd=self._buck_dir, env=get_ant_env(JAVA_MAX_HEAP_SIZE_MB))
            if exitcode is not 0:
                self._print_ant_failure_and_exit(ant_log_path)

    def _build(self):
        with Tracing('BuckRepo._build'):
            if not os.path.exists(self._build_success_file):
                print(
                    "Buck does not appear to have been built -- building Buck!",
                    file=sys.stderr)
                ant = self._check_for_ant()
                self._run_ant_clean(ant)
                self._run_ant(ant)
                print("All done, continuing with build.", file=sys.stderr)

    def _get_resource_lock_path(self):
        return None

    def _has_resource(self, resource):
        return True

    def _get_resource(self, resource, exe=False):
        return self._join_buck_dir(RESOURCES[resource.name])

    def _get_buck_version_uid(self):
        with Tracing('BuckRepo._get_buck_version_uid'):

            # Check if the developer has requested that we impersonate some other version.
            fake_buck_version_file_path = os.path.join(self._buck_dir, ".fakebuckversion")
            if os.path.exists(fake_buck_version_file_path):
                with open(fake_buck_version_file_path) as fake_buck_version_file:
                    fake_buck_version = fake_buck_version_file.read().strip()

                print(textwrap.dedent("""\
                ::: Faking buck version %s, despite your buck directory not being that version."""
                                      % fake_buck_version),
                      file=sys.stderr)
                return fake_buck_version

            # First try to get the "clean" buck version.  If it succeeds,
            # return it.
            clean_version = buck_version.get_clean_buck_version(
                self._buck_dir,
                allow_dirty=self._is_buck_repo_dirty_override == "1")
            if clean_version is not None:
                return clean_version

            # Otherwise, if there is a .nobuckcheck file, or if there isn't
            # a .buckversion file, fall back to a "dirty" version.
            if (self._buck_project.has_no_buck_check or
                    not self._buck_project.buck_version):
                return buck_version.get_dirty_buck_version(self._buck_dir)

            if self._has_local_changes():
                print(textwrap.dedent("""\
                ::: Your buck directory has local modifications, and therefore
                ::: builds will not be able to use a distributed cache.
                ::: The following files must be either reverted or committed:"""),
                      file=sys.stderr)
                subprocess.call(
                    ['git', 'ls-files', '-m'],
                    stdout=sys.stderr,
                    cwd=self._buck_dir)
            elif os.environ.get('BUCK_CLEAN_REPO_IF_DIRTY') != 'NO':
                print(textwrap.dedent("""\
                ::: Your local buck directory is dirty, and therefore builds will
                ::: not be able to use a distributed cache."""), file=sys.stderr)
                if sys.stdout.isatty():
                    print(
                        "::: Do you want to clean your buck directory? [y/N]",
                        file=sys.stderr)
                    choice = raw_input().lower()
                    if choice == "y":
                        subprocess.call(
                            ['git', 'clean', '-fd'],
                            stdout=sys.stderr,
                            cwd=self._buck_dir)
                        raise RestartBuck()

            return buck_version.get_dirty_buck_version(self._buck_dir)

    def _is_buck_production(self):
        return False

    def _get_extra_java_args(self):
        with Tracing('BuckRepo._get_extra_java_args'):
            return [
                "-Dbuck.git_commit={0}".format(self._get_git_revision()),
                "-Dbuck.git_commit_timestamp={0}".format(
                    self._get_git_commit_timestamp()),
                "-Dbuck.git_dirty={0}".format(
                  int(self._is_buck_repo_dirty_override == "1" or
                      buck_version.is_dirty(self._buck_dir))),
            ]

    def _get_bootstrap_classpath(self):
        return self._join_buck_dir("build/bootstrapper/bootstrapper.jar")

    def _get_java_classpath(self):
        return self._pathsep.join([self._join_buck_dir(p) for p in JAVA_CLASSPATHS])

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        pass
