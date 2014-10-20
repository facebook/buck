from __future__ import print_function
import os
import platform
import re
import signal
import subprocess
import sys
import tempfile
import textwrap
import time

from timing import monotonic_time_nanos
from tracing import Tracing

# We use pty to launch buckd. For now, we don't support buckd on Windows, which is fortunate
# because it turns out pty doesn't work on Windows either. Oh! Happy happenstance!
if platform.system() != 'Windows':
    import pty

JAVA_CLASSPATHS = [
    "src",
    "build/abi_processor/classes",
    "build/classes",
    "build/dx_classes",
    "third-party/java/aopalliance/aopalliance.jar",
    "third-party/java/args4j/args4j-2.0.30.jar",
    "third-party/java/ddmlib/ddmlib-22.5.3.jar",
    "third-party/java/guava/guava-18.0.jar",
    "third-party/java/guice/guice-3.0.jar",
    "third-party/java/guice/guice-assistedinject-3.0.jar",
    "third-party/java/guice/guice-multibindings-3.0.jar",
    "third-party/java/ini4j/ini4j-0.5.2.jar",
    "third-party/java/jackson/jackson-annotations-2.0.5.jar",
    "third-party/java/jackson/jackson-core-2.0.5.jar",
    "third-party/java/jackson/jackson-databind-2.0.5.jar",
    "third-party/java/jsr/javax.inject-1.jar",
    "third-party/java/jsr/jsr305.jar",
    "third-party/java/icu4j/icu4j-54.1.1.jar",
    "third-party/java/nailgun/nailgun-server-0.9.2-SNAPSHOT.jar",
    "third-party/java/android/sdklib.jar",
    "third-party/java/asm/asm-debug-all-5.0.3.jar",
    "third-party/java/astyanax/astyanax-cassandra-1.56.38.jar",
    "third-party/java/astyanax/astyanax-core-1.56.38.jar",
    "third-party/java/astyanax/astyanax-thrift-1.56.38.jar",
    "third-party/java/astyanax/cassandra-1.2.3.jar",
    "third-party/java/astyanax/cassandra-thrift-1.2.3.jar",
    "third-party/java/astyanax/commons-cli-1.1.jar",
    "third-party/java/astyanax/commons-codec-1.2.jar",
    "third-party/java/astyanax/commons-lang-2.6.jar",
    "third-party/java/astyanax/high-scale-lib-1.1.2.jar",
    "third-party/java/astyanax/joda-time-2.2.jar",
    "third-party/java/astyanax/libthrift-0.7.0.jar",
    "third-party/java/astyanax/log4j-1.2.16.jar",
    "third-party/java/astyanax/slf4j-api-1.7.2.jar",
    "third-party/java/astyanax/slf4j-log4j12-1.7.2.jar",
    "third-party/java/closure-templates/soy-excluding-deps.jar",
    "third-party/java/gson/gson-2.2.4.jar",
    "third-party/java/eclipse/org.eclipse.core.contenttype_3.4.200.v20130326-1255.jar",
    "third-party/java/eclipse/org.eclipse.core.jobs_3.5.300.v20130429-1813.jar",
    "third-party/java/eclipse/org.eclipse.core.resources_3.8.101.v20130717-0806.jar",
    "third-party/java/eclipse/org.eclipse.core.runtime_3.9.100.v20131218-1515.jar",
    "third-party/java/eclipse/org.eclipse.equinox.common_3.6.200.v20130402-1505.jar",
    "third-party/java/eclipse/org.eclipse.equinox.preferences_3.5.100.v20130422-1538.jar",
    "third-party/java/eclipse/org.eclipse.jdt.core_3.9.2.v20140114-1555.jar",
    "third-party/java/eclipse/org.eclipse.osgi_3.9.1.v20140110-1610.jar",
    "third-party/java/dd-plist/dd-plist.jar",
    "third-party/java/jetty/jetty-all-9.0.4.v20130625.jar",
    "third-party/java/jetty/servlet-api.jar",
    "third-party/java/xz-java-1.3/xz-1.3.jar",
    "third-party/java/commons-compress/commons-compress-1.8.1.jar",
]

BUCK_DIR_JAVA_ARGS = {
    "testrunner_classes": "build/testrunner/classes",
    "abi_processor_classes": "build/abi_processor/classes",
    "path_to_emma_jar": "third-party/java/emma-2.0.5312/out/emma-2.0.5312.jar",
    "path_to_asm_jar": "third-party/java/asm/asm-debug-all-5.0.3.jar",
    "logging_config_file": "config/logging.properties",
    "path_to_buck_py": "src/com/facebook/buck/parser/buck.py",
    "path_to_pathlib_py": "third-party/py/pathlib/pathlib.py",

    "path_to_compile_asset_catalogs_py":
    "src/com/facebook/buck/apple/compile_asset_catalogs.py",

    "path_to_compile_asset_catalogs_build_phase_sh":
    "src/com/facebook/buck/apple/compile_asset_catalogs_build_phase.sh",

    "path_to_intellij_py": "src/com/facebook/buck/command/intellij.py",
    "jacoco_agent_jar": "third-party/java/jacoco/jacocoagent.jar",
    "path_to_static_content": "webserver/static",
    "path_to_pex": "src/com/facebook/buck/python/pex.py",
    "quickstart_origin_dir": "src/com/facebook/buck/cli/quickstart/android",
    "dx": "third-party/java/dx-from-kitkat/etc/dx",
    "android_agent_path": "assets/android/agent.apk"
}

MAX_BUCKD_RUN_COUNT = 64
BUCKD_CLIENT_TIMEOUT_MILLIS = 60000
GC_MAX_PAUSE_TARGET = 15000

BUCKD_LOG_FILE_PATTERN = re.compile('^NGServer.* port (\d+)\.$')
NAILGUN_CONNECTION_REFUSED_CODE = 230
# TODO(natthu): CI servers, for some reason, encounter this error.
# For now, simply skip this error. Need to figure out why this happens.
NAILGUN_UNEXPECTED_CHUNK_TYPE = 229
NAILGUN_CONNECTION_BROKEN_CODE = 227


class Command:
    BUCK = "buck"
    BUCKD = "buckd"


class BuckRepo:

    def __init__(self, buck_bin_dir, buck_project, launch_command):
        self._buck_bin_dir = buck_bin_dir
        self._buck_dir = self._platform_path(os.path.dirname(self._buck_bin_dir))
        self._build_success_file = os.path.join(
            self._buck_dir, "build", "successful-build")
        self._buck_client_file = os.path.join(
            self._buck_dir, "build", "ng")

        self._buck_project = buck_project
        self._tmp_dir = self._platform_path(buck_project.tmp_dir)

        self._pathsep = os.pathsep
        if (sys.platform == 'cygwin'):
            self._pathsep = ';'

        self._launch_command = launch_command

        dot_git = os.path.join(self._buck_dir, '.git')
        self._is_git = os.path.exists(dot_git) and os.path.isdir(dot_git) and which('git') and \
            sys.platform != 'cygwin'
        self._is_buck_repo_dirty_override = os.environ.get('BUCK_REPOSITORY_DIRTY')

        buck_version = buck_project.buck_version
        if self._is_git and not buck_project.has_no_buck_check and buck_version:
            revision = buck_version[0]
            branch = buck_version[1] if len(buck_version) > 1 else None
            self._checkout_and_clean(revision, branch)

        self._buck_version_uid = self._get_buck_version_uid()
        self._build()

    def launch_buck(self, build_id):
        with Tracing('BuckRepo.launch_buck'):
            self.kill_autobuild()
            if 'clean' in sys.argv:
                self.kill_buckd()

            use_buckd = not os.environ.get('NO_BUCKD')
            has_watchman = bool(which('watchman'))
            if use_buckd and has_watchman:
                buckd_run_count = self._buck_project.get_buckd_run_count()
                running_version = self._buck_project.get_running_buckd_version()
                new_buckd_run_count = buckd_run_count + 1

                if (buckd_run_count == MAX_BUCKD_RUN_COUNT or
                        running_version != self._buck_version_uid):
                    self.kill_buckd()
                    new_buckd_run_count = 0

                if new_buckd_run_count == 0 or not self._is_buckd_running():
                    self.launch_buckd()
                else:
                    self._buck_project.update_buckd_run_count(new_buckd_run_count)
            elif use_buckd and not has_watchman:
                print("Not using buckd because watchman isn't installed.",
                      file=sys.stderr)

            env = os.environ.copy()
            env['BUCK_BUILD_ID'] = build_id

            if use_buckd and self._is_buckd_running() and os.path.exists(self._buck_client_file):
                print("Using buckd.", file=sys.stderr)
                buckd_port = self._buck_project.get_buckd_port()
                if not buckd_port or not buckd_port.isdigit():
                    print(
                        "Daemon port file is corrupt, starting new buck process.",
                        file=sys.stderr)
                    self.kill_buckd()
                else:
                    command = [self._buck_client_file]
                    command.append("--nailgun-port")
                    command.append(buckd_port)
                    command.append("com.facebook.buck.cli.Main")
                    command.extend(sys.argv[1:])
                    with Tracing('buck', args={'command': command}):
                        exit_code = subprocess.call(command, cwd=self._buck_project.root, env=env)
                        if exit_code == 2:
                            print('Daemon is busy, please wait',
                                  'or run "buckd --kill" to terminate it.',
                                  file=sys.stderr)
                        return exit_code

            command = [which("java")]
            command.extend(self._get_java_args(self._buck_version_uid))
            command.append("-Djava.io.tmpdir={0}".format(self._tmp_dir))
            command.append("-classpath")
            command.append(self._get_java_classpath())
            command.append("com.facebook.buck.cli.Main")
            command.extend(sys.argv[1:])

            return subprocess.call(command, cwd=self._buck_project.root, env=env)

    def launch_buckd(self):
        with Tracing('BuckRepo.launch_buckd'):
            self._setup_watchman_watch()
            self._buck_project.create_buckd_tmp_dir()
            # Override self._tmp_dir to a long lived directory.
            buckd_tmp_dir = self._buck_project.buckd_tmp_dir

            '''
            Use SoftRefLRUPolicyMSPerMB for immediate GC of javac output.
            Set timeout to 60s (longer than the biggest GC pause seen for a 2GB
            heap) and GC target to 15s. This means that the GC has to miss its
            target by 100% or many 500ms heartbeats must be missed before a client
            disconnection occurs. Specify port 0 to allow Nailgun to find an
            available port, then parse the port number out of the first log entry.
            '''
            command = [which("java")]
            command.extend(self._get_java_args(self._buck_version_uid))
            command.append("-Dbuck.buckd_launch_time_nanos={0}".format(monotonic_time_nanos()))
            command.append("-Dbuck.buckd_watcher=Watchman")
            command.append("-XX:MaxGCPauseMillis={0}".format(GC_MAX_PAUSE_TARGET))
            command.append("-XX:SoftRefLRUPolicyMSPerMB=0")
            command.append("-Djava.io.tmpdir={0}".format(buckd_tmp_dir))
            command.append("-classpath")
            command.append(self._get_java_classpath())
            command.append("com.martiansoftware.nailgun.NGServer")
            command.append("localhost:0")
            command.append("{0}".format(BUCKD_CLIENT_TIMEOUT_MILLIS))

            '''
            We want to launch the buckd process in such a way that it finds the
            terminal as a tty while being able to read its output. We also want to
            shut up any nailgun output. If we simply redirect stdout/stderr to a
            file, the super console no longer works on subsequent invocations of
            buck. So use a pseudo-terminal to interact with it.
            '''
            master, slave = pty.openpty()

            '''
            Change the process group of the child buckd process so that when this
            script is interrupted, it does not kill buckd.
            '''
            def preexec_func():
                os.setpgrp()

            process = subprocess.Popen(
                command,
                cwd=self._buck_project.root,
                stdout=slave,
                stderr=slave,
                preexec_fn=preexec_func)
            stdout = os.fdopen(master)

            for i in range(100):
                line = stdout.readline().strip()
                match = BUCKD_LOG_FILE_PATTERN.match(line)
                if match:
                    buckd_port = match.group(1)
                    break
                time.sleep(0.1)
            else:
                print(
                    "nailgun server did not respond after 10s. Aborting buckd.",
                    file=sys.stderr)
                return

            self._buck_project.save_buckd_port(buckd_port)
            self._buck_project.save_buckd_version(self._buck_version_uid)
            self._buck_project.update_buckd_run_count(0)

    def kill_autobuild(self):
        autobuild_pid = self._buck_project.get_autobuild_pid()
        if autobuild_pid:
            if autobuild_pid.isdigit():
                try:
                    os.kill(autobuild_pid, signal.SIGTERM)
                except OSError:
                    pass

    def kill_buckd(self):
        with Tracing('BuckRepo.kill_buckd'):
            buckd_port = self._buck_project.get_buckd_port()

            if buckd_port:
                if not buckd_port.isdigit():
                    print("WARNING: Corrupt buckd port: '{0}'.".format(buckd_port))
                else:
                    print("Shutting down nailgun server...", file=sys.stderr)
                    command = [self._buck_client_file]
                    command.append('ng-stop')
                    command.append('--nailgun-port')
                    command.append(buckd_port)
                    try:
                        check_output(
                            command,
                            cwd=self._buck_project.root,
                            stderr=subprocess.STDOUT)
                    except CalledProcessError as e:
                        if (e.returncode not in
                                [NAILGUN_CONNECTION_REFUSED_CODE,
                                    NAILGUN_CONNECTION_BROKEN_CODE,
                                    NAILGUN_UNEXPECTED_CHUNK_TYPE]):
                            print(e.output, end='', file=sys.stderr)
                            raise

            self._buck_project.clean_up_buckd()

    def _setup_watchman_watch(self):
        with Tracing('BuckRepo._setup_watchman_watch'):
            if not which('watchman'):
                message = textwrap.dedent("""\
                    Watchman not found, please install when using buckd.
                    See https://github.com/facebook/watchman for details.""")
                if sys.platform == "darwin":
                    message += "\n(brew install --HEAD watchman on OS X)"
                # Bail if watchman isn't installed as we know java's
                # FileSystemWatcher will take too long to process events.
                raise BuckRepoException(message)

            print("Using watchman.", file=sys.stderr)
            try:
                check_output(
                    ['watchman', 'watch', self._buck_project.root],
                    stderr=subprocess.STDOUT)
            except CalledProcessError as e:
                print(e.output, end='', file=sys.stderr)
                raise

    def _is_buckd_running(self):
        with Tracing('BuckRepo._is_buckd_running'):
            buckd_port = self._buck_project.get_buckd_port()

            if buckd_port is None or not buckd_port.isdigit():
                return False

            command = [self._buck_client_file]
            command.append('ng-stats')
            command.append('--nailgun-port')
            command.append(buckd_port)
            try:
                check_output(
                    command,
                    cwd=self._buck_project.root,
                    stderr=subprocess.STDOUT)
            except CalledProcessError as e:
                if e.returncode == NAILGUN_CONNECTION_REFUSED_CODE:
                    return False
                else:
                    print(e.output, end='', file=sys.stderr)
                    raise

            return True

    def _checkout_and_clean(self, revision, branch):
        with Tracing('BuckRepo._checkout_and_clean'):
            if not self._revision_exists(revision):
                git_command = ['git', 'fetch']
                git_command.extend(['--all'] if not branch else ['origin', branch])
                try:
                    subprocess.check_call(
                        git_command,
                        stdout=sys.stderr,
                        cwd=self._buck_dir)
                except subprocess.CalledProcessError:
                    raise BuckRepoException(textwrap.dedent("""\
                          Failed to fetch Buck updates from git.
                          You can disable this by creating a '.nobuckcheck' file in
                          your repository, but this might lead to strange bugs or
                          build failures."""))

            current_revision = self._get_git_revision()

            if current_revision != revision:
                print(textwrap.dedent("""\
                    Buck is at {0}, but should be {1}.
                    Buck is updating itself. To disable this, add a '.nobuckcheck'
                    file to your project root. In general, you should only disable
                    this if you are developing Buck.""".format(
                    current_revision, revision)),
                    file=sys.stderr)

                subprocess.check_call(
                    ['git', 'checkout', '--quiet', revision],
                    cwd=self._buck_dir)
                if os.path.exists(self._build_success_file):
                    os.remove(self._build_success_file)

                ant = self._check_for_ant()
                self._run_ant_clean(ant)
                self._restart_buck()

    def _join_buck_dir(self, relative_path):
        return os.path.join(self._buck_dir, *(relative_path.split('/')))

    def _is_dirty(self):
        if self._is_buck_repo_dirty_override:
            return self._is_buck_repo_dirty_override == "1"

        if not self._is_git:
            return False

        output = check_output(
            ['git', 'status', '--porcelain'],
            cwd=self._buck_dir)
        return bool(output.strip())

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

        output = check_output(
            ['git', 'rev-parse', 'HEAD', '--'],
            cwd=self._buck_dir)
        return output.splitlines()[0].strip()

    def _get_git_commit_timestamp(self):
        if self._is_buck_repo_dirty_override or not self._is_git:
            return -1

        return check_output(
            ['git', 'log', '--pretty=format:%ct', '-1', 'HEAD', '--'],
            cwd=self._buck_dir).strip()

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
            raise BuckRepoException(message)
        return ant

    def _print_ant_failure_and_exit(self, ant_log_path):
        print(textwrap.dedent("""\
                ::: 'ant' failed in the buck repo at '{0}',
                ::: and 'buck' is not properly built. It will be unusable
                ::: until the error is corrected. You can check the logs
                ::: at {1} to figure out what broke.""".format(
              self._buck_dir, ant_log_path)), file=sys.stderr)
        if self._is_git:
            raise BuckRepoException(textwrap.dedent("""\
                ::: It is possible that running this command will fix it:
                ::: git -C "{0}" clean -xfd""".format(self._buck_dir)))
        else:
            raise BuckRepoException(textwrap.dedent("""\
                ::: It is possible that running this command will fix it:
                ::: rm -rf "{0}"/build""".format(self._buck_dir)))

    def _run_ant_clean(self, ant):
        clean_log_path = os.path.join(self._buck_project.get_buck_out_log_dir(), 'ant-clean.log')
        with open(clean_log_path, 'w') as clean_log:
            exitcode = subprocess.call([ant, 'clean'], stdout=clean_log,
                                       cwd=self._buck_dir)
            if exitcode is not 0:
                self._print_ant_failure_and_exit(clean_log_path)

    def _run_ant(self, ant):
        ant_log_path = os.path.join(self._buck_project.get_buck_out_log_dir(), 'ant.log')
        with open(ant_log_path, 'w') as ant_log:
            exitcode = subprocess.call([ant], stdout=ant_log,
                                       cwd=self._buck_dir)
            if exitcode is not 0:
                self._print_ant_failure_and_exit(ant_log_path)

    def _restart_buck(self):
        command = [os.path.join(self._buck_bin_dir, self._launch_command)]
        command.extend(sys.argv[1:])
        exitcode = subprocess.call(command, stdout=sys.stderr)
        if exitcode < 0:
            os.kill(os.getpid(), -exitcode)
        else:
            sys.exit(exitcode)

    def _compute_local_hash(self):
        git_tree_in = check_output(
            ['git', 'log', '-n1', '--pretty=format:%T', 'HEAD', '--'],
            cwd=self._buck_dir).strip()

        with EmptyTempFile(prefix='buck-git-index',
                           dir=self._tmp_dir) as index_file:
            new_environ = os.environ.copy()
            new_environ['GIT_INDEX_FILE'] = index_file.name
            subprocess.check_call(
                ['git', 'read-tree', git_tree_in],
                cwd=self._buck_dir,
                env=new_environ)

            subprocess.check_call(
                ['git', 'add', '-u'],
                cwd=self._buck_dir,
                env=new_environ)

            git_tree_out = check_output(
                ['git', 'write-tree'],
                cwd=self._buck_dir,
                env=new_environ).strip()

        with EmptyTempFile(prefix='buck-version-uid-input',
                           dir=self._tmp_dir,
                           closed=False) as uid_input:
            subprocess.check_call(
                ['git', 'ls-tree', '--full-tree', git_tree_out],
                cwd=self._buck_dir,
                stdout=uid_input)
            return check_output(
                ['git', 'hash-object', uid_input.name],
                cwd=self._buck_dir).strip()

    def _build(self):
        with Tracing('BuckRepo._build'):
            if not os.path.exists(self._build_success_file):
                print(
                    "Buck does not appear to have been built -- building Buck!",
                    file=sys.stderr)
                ant = self._check_for_ant()
                self._run_ant_clean(ant)
                self._run_ant(ant)
                open(self._build_success_file, 'w').close()

    def _get_buck_version_uid(self):
        with Tracing('BuckRepo._get_buck_version_uid'):
            if not self._is_git:
                return 'N/A'

            if not self._is_dirty():
                return self._get_git_revision()

            if (self._buck_project.has_no_buck_check or
                    not self._buck_project.buck_version):
                return self._compute_local_hash()

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
                        self._restart_buck()

            return self._compute_local_hash()

    def _get_java_args(self, version_uid):
        java_args = [] if is_java8() else ["-XX:MaxPermSize=256m"]
        java_args.extend([
            "-Xmx1000m",
            "-Djava.awt.headless=true",
            "-Djava.util.logging.config.class=com.facebook.buck.log.LogConfig",
            "-Dbuck.test_util_no_tests_dir=true",
            "-Dbuck.git_commit={0}".format(self._get_git_revision()),
            "-Dbuck.git_commit_timestamp={0}".format(
                self._get_git_commit_timestamp()),
            "-Dbuck.version_uid={0}".format(version_uid),
            "-Dbuck.git_dirty={0}".format(int(self._is_dirty())),
            "-Dbuck.buckd_dir={0}".format(self._buck_project.buckd_dir),
            "-Dbuck.buck_dir={0}".format(self._buck_dir),
            "-Dlog4j.configuration=file:{0}".format(
                self._join_buck_dir("config/log4j.properties")),
        ])
        for key, value in BUCK_DIR_JAVA_ARGS.items():
            java_args.append("-Dbuck.{0}={1}".format(
                             key, self._join_buck_dir(value)))

        if os.environ.get("BUCK_DEBUG_MODE"):
            java_args.append("-agentlib:jdwp=transport=dt_socket,"
                             "server=y,suspend=y,address=8888")

        if os.environ.get("BUCK_DEBUG_SOY"):
            java_args.append("-Dbuck.soy.debug=true")

        if self._buck_project.buck_javaargs:
            java_args.extend(self._buck_project.buck_javaargs.split(' '))

        extra_java_args = os.environ.get("BUCK_EXTRA_JAVA_ARGS")
        if extra_java_args:
            java_args.extend(extra_java_args.split(' '))
        return java_args

    def _platform_path(self, path):
        if sys.platform != 'cygwin':
            return path
        return subprocess.check_output(['cygpath', '-w', path]).strip()

    def _get_java_classpath(self):
        return self._pathsep.join([self._join_buck_dir(p) for p in JAVA_CLASSPATHS])


class BuckRepoException(Exception):
    pass


class EmptyTempFile:
    def __init__(self, prefix=None, dir=None, closed=True):
        self.file, self.name = tempfile.mkstemp(prefix=prefix, dir=dir)
        if closed:
            os.close(self.file)
        self.closed = closed

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.close()
        os.remove(self.name)

    def close(self):
        if not self.closed:
            os.close(self.file)
        self.closed = True

    def fileno(self):
        return self.file


#
# an almost exact copy of the shutil.which() implementation from python3.4
#
def which(cmd, mode=os.F_OK | os.X_OK, path=None):
    """Given a command, mode, and a PATH string, return the path which
    conforms to the given mode on the PATH, or None if there is no such
    file.

    `mode` defaults to os.F_OK | os.X_OK. `path` defaults to the result
    of os.environ.get("PATH"), or can be overridden with a custom search
    path.

    """
    # Check that a given file can be accessed with the correct mode.
    # Additionally check that `file` is not a directory, as on Windows
    # directories pass the os.access check.
    def _access_check(fn, mode):
        return (os.path.exists(fn) and os.access(fn, mode)
                and not os.path.isdir(fn))

    # If we're given a path with a directory part, look it up directly rather
    # than referring to PATH directories. This includes checking relative to
    # the current directory, e.g. ./script
    if os.path.dirname(cmd):
        if _access_check(cmd, mode):
            return cmd
        return None

    if path is None:
        path = os.environ.get("PATH", os.defpath)
    if not path:
        return None
    path = path.split(os.pathsep)

    if sys.platform == "win32":
        # The current directory takes precedence on Windows.
        if os.curdir not in path:
            path.insert(0, os.curdir)

        # PATHEXT is necessary to check on Windows.
        pathext = os.environ.get("PATHEXT", "").split(os.pathsep)
        # See if the given file matches any of the expected path extensions.
        # This will allow us to short circuit when given "python.exe".
        # If it does match, only test that one, otherwise we have to try
        # others.
        if any(cmd.lower().endswith(ext.lower()) for ext in pathext):
            files = [cmd]
        else:
            files = [cmd + ext for ext in pathext]
    else:
        # On other platforms you don't have things like PATHEXT to tell you
        # what file suffixes are executable, so just pass on cmd as-is.
        files = [cmd]

    seen = set()
    for dir in path:
        normdir = os.path.normcase(dir)
        if normdir not in seen:
            seen.add(normdir)
            for thefile in files:
                name = os.path.join(dir, thefile)
                if _access_check(name, mode):
                    return name
    return None


# Backport of the Python 2.7 subprocess.CalledProcessError, including
# an `output` attribute.
class CalledProcessError(subprocess.CalledProcessError):
    def __init__(self, returncode, cmd, output=None):
        super(CalledProcessError, self).__init__(returncode, cmd)
        self.output = output


# Backport of the Python 2.7 subprocess.check_output. Taken from
# http://hg.python.org/cpython/file/71cb8f605f77/Lib/subprocess.py
# Copyright (c) 2003-2005 by Peter Astrand <astrand@lysator.liu.se>
# Licensed to PSF under a Contributor Agreement.
# See http://www.python.org/2.4/license for licensing details.
def check_output(*popenargs, **kwargs):
    if 'stdout' in kwargs:
        raise ValueError('stdout argument not allowed, it will be overridden.')
    process = subprocess.Popen(stdout=subprocess.PIPE, *popenargs, **kwargs)
    output, unused_err = process.communicate()
    retcode = process.poll()
    if retcode:
        cmd = kwargs.get("args")
        if cmd is None:
            cmd = popenargs[0]
        raise CalledProcessError(retcode, cmd, output=output)
    return output


def is_java8():
    output = check_output([which('java'), '-version'], stderr=subprocess.STDOUT)
    version_line = output.strip().splitlines()[0]
    return re.compile(b'java version "1\.8\..*').match(version_line)
