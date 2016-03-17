from __future__ import print_function
import errno
import json
import os
import platform
import re
import shlex
import signal
import subprocess
import sys
import tempfile
import textwrap
import time
import traceback

from pynailgun import NailgunConnection, NailgunException
from timing import monotonic_time_nanos
from tracing import Tracing
from subprocutils import check_output, CalledProcessError

MAX_BUCKD_RUN_COUNT = 64
BUCKD_CLIENT_TIMEOUT_MILLIS = 60000
GC_MAX_PAUSE_TARGET = 15000

JAVA_MAX_HEAP_SIZE_MB = 1000

# While waiting for the daemon to terminate, print a message at most
# every DAEMON_BUSY_MESSAGE_SECONDS seconds.
DAEMON_BUSY_MESSAGE_SECONDS = 1.0

# Describes a resource used by this driver.
#  - name: logical name of the resources
#  - executable: whether the resource should/needs execute permissions
#  - basename: required basename of the resource
class Resource(object):
    def __init__(self, name, executable=False, basename=None):
        self.name = name
        self.executable = executable
        self.basename = name if basename is None else basename


# Resource that get propagated to buck via system properties.
EXPORTED_RESOURCES = [
    Resource("testrunner_classes"),
    Resource("abi_processor_classes"),
    Resource("path_to_asm_jar"),
    Resource("logging_config_file"),
    Resource("path_to_pathlib_py", basename='pathlib.py'),
    Resource("path_to_intellij_py"),
    Resource("path_to_pex"),
    Resource("path_to_pywatchman"),
    Resource("path_to_sh_binary_template"),
    Resource("jacoco_agent_jar"),
    Resource("report_generator_jar"),
    Resource("path_to_static_content"),
    Resource("path_to_pex", executable=True),
    Resource("dx"),
    Resource("android_agent_path"),
    Resource("native_exopackage_fake_path"),
]


class CommandLineArgs:
    def __init__(self, cmdline):
        self.args = cmdline[1:]

        self.buck_options = []
        self.command = None
        self.command_options = []

        for arg in self.args:
            if (self.command is not None):
                self.command_options.append(arg)
            elif (arg[:1]) == "-":
                self.buck_options.append(arg)
            else:
                self.command = arg

    # Whether this is a help command that doesn't run a build
    # n.b. 'buck --help clean' is *not* currently a help command
    # n.b. 'buck --version' *is* a help command
    def is_help(self):
        return self.command is None or "--help" in self.command_options


class RestartBuck(Exception):
    pass


class BuckToolException(Exception):
    pass


class BuckTool(object):

    def __init__(self, buck_project):
        self._command_line = CommandLineArgs(sys.argv)
        self._buck_project = buck_project
        self._tmp_dir = self._platform_path(buck_project.tmp_dir)

        self._pathsep = os.pathsep
        if (sys.platform == 'cygwin'):
            self._pathsep = ';'

    # Check whether the given resource exists.
    def _has_resource(self, resource):
        raise NotImplementedError()

    # Return an on-disk path to the given resource.  This may cause
    # implementations to unpack the resource at this point.
    def _get_resource(self, resource):
        raise NotImplementedError()

    def _use_buckd(self):
        return not os.environ.get('NO_BUCKD') and not self._command_line.is_help()

    def _environ_for_buck(self):
        env = os.environ.copy()
        env['CLASSPATH'] = str(self._get_bootstrap_classpath())
        env['BUCK_CLASSPATH'] = str(self._get_java_classpath())
        env['BUCK_TTY'] = str(int(sys.stdin.isatty()))
        # Buck overwrites these variables for a few purposes.
        # Pass them through with their original values for
        # tests that need them.
        for f in ('TEMPDIR', 'TEMP', 'TMPDIR', 'TMP'):
            orig_value = env.get(f)
            if orig_value is not None:
                env['BUCK_ORIG_' + f] = orig_value
        return env

    def launch_buck(self, build_id):
        with Tracing('BuckRepo.launch_buck'):
            if self._command_line.command == "clean" and not self._command_line.is_help():
                self.kill_buckd()

            buck_version_uid = self._get_buck_version_uid()

            use_buckd = self._use_buckd()
            if not self._command_line.is_help():
                has_watchman = bool(which('watchman'))
                if use_buckd and has_watchman:
                    buckd_run_count = self._buck_project.get_buckd_run_count()
                    running_version = self._buck_project.get_running_buckd_version()
                    new_buckd_run_count = buckd_run_count + 1

                    if (buckd_run_count == MAX_BUCKD_RUN_COUNT or
                            running_version != buck_version_uid):
                        self.kill_buckd()
                        new_buckd_run_count = 0

                    if new_buckd_run_count == 0 or not self._is_buckd_running():
                        self.launch_buckd(buck_version_uid=buck_version_uid)
                    else:
                        self._buck_project.update_buckd_run_count(new_buckd_run_count)
                elif use_buckd and not has_watchman:
                    print("Not using buckd because watchman isn't installed.",
                          file=sys.stderr)
                elif not use_buckd:
                    print("Not using buckd because NO_BUCKD is set.",
                          file=sys.stderr)

            env = self._environ_for_buck()
            env['BUCK_BUILD_ID'] = build_id

            buck_socket_path = self._buck_project.get_buckd_socket_path()

            if use_buckd and self._is_buckd_running() and \
                    os.path.exists(buck_socket_path):
                with Tracing('buck', args={'command': sys.argv[1:]}):
                    exit_code = 2
                    last_diagnostic_time = 0
                    while exit_code == 2:
                        with NailgunConnection('local:.buckd/sock',
                                               cwd=self._buck_project.root) as c:
                            exit_code = c.send_command(
                                'com.facebook.buck.cli.Main',
                                sys.argv[1:],
                                env=env,
                                cwd=self._buck_project.root)
                            if exit_code == 2:
                                now = time.time()
                                if now - last_diagnostic_time > DAEMON_BUSY_MESSAGE_SECONDS:
                                    print('Daemon is busy, waiting for it to become free...',
                                          file=sys.stderr)
                                    last_diagnostic_time = now
                                time.sleep(0.1)
                    return exit_code


            command = ["buck"]
            extra_default_options = [
                "-Djava.io.tmpdir={0}".format(self._tmp_dir)
            ]
            command.extend(self._get_java_args(buck_version_uid, extra_default_options))
            command.append("com.facebook.buck.cli.bootstrapper.ClassLoaderBootstrapper")
            command.append("com.facebook.buck.cli.Main")
            command.extend(sys.argv[1:])

            return subprocess.call(command,
                                   cwd=self._buck_project.root,
                                   env=env,
                                   executable=which("java"))

    def launch_buckd(self, buck_version_uid=None):
        with Tracing('BuckRepo.launch_buckd'):
            self._setup_watchman_watch()
            if buck_version_uid is None:
                buck_version_uid = self._get_buck_version_uid()
            # Override self._tmp_dir to a long lived directory.
            buckd_tmp_dir = self._buck_project.create_buckd_tmp_dir()
            ngserver_output_path = os.path.join(buckd_tmp_dir, 'ngserver-out')

            '''
            Use SoftRefLRUPolicyMSPerMB for immediate GC of javac output.
            Set timeout to 60s (longer than the biggest GC pause seen for a 2GB
            heap) and GC target to 15s. This means that the GC has to miss its
            target by 100% or many 500ms heartbeats must be missed before a client
            disconnection occurs. Specify port 0 to allow Nailgun to find an
            available port, then parse the port number out of the first log entry.
            '''
            command = ["buckd"]
            extra_default_options = [
                "-Dbuck.buckd_launch_time_nanos={0}".format(monotonic_time_nanos()),
                "-XX:MaxGCPauseMillis={0}".format(GC_MAX_PAUSE_TARGET),
                "-XX:SoftRefLRUPolicyMSPerMB=0",
                # Stop Java waking up every 50ms to collect thread
                # statistics; doing it once every five seconds is much
                # saner for a long-lived daemon.
                "-XX:PerfDataSamplingInterval=5000",
                # Do not touch most signals
                "-Xrs",
                # Likewise, waking up once per second just in case
                # there's some rebalancing to be done is silly.
                "-XX:+UnlockDiagnosticVMOptions",
                "-XX:GuaranteedSafepointInterval=5000",
                "-Djava.io.tmpdir={0}".format(buckd_tmp_dir),
                "-Dcom.martiansoftware.nailgun.NGServer.outputPath={0}".format(
                    ngserver_output_path),
            ]

            if is_java8():
                extra_default_options.extend([
                    "-XX:+UseG1GC",
                    "-XX:MaxHeapFreeRatio=40",
                ])

            command.extend(self._get_java_args(buck_version_uid, extra_default_options))
            command.append("com.facebook.buck.cli.bootstrapper.ClassLoaderBootstrapper")
            command.append("com.facebook.buck.cli.Main$DaemonBootstrap")
            command.append("local:.buckd/sock")
            command.append("{0}".format(BUCKD_CLIENT_TIMEOUT_MILLIS))

            '''
            Change the process group of the child buckd process so that when this
            script is interrupted, it does not kill buckd.
            '''
            def preexec_func():
                # Close any open file descriptors to further separate buckd from its
                # invoking context (e.g. otherwise we'd hang when running things like
                # `ssh localhost buck clean`).
                dev_null_fd = os.open("/dev/null", os.O_RDWR)
                os.dup2(dev_null_fd, 0)
                os.dup2(dev_null_fd, 1)
                os.dup2(dev_null_fd, 2)
                os.close(dev_null_fd)
            buck_socket_path = self._buck_project.get_buckd_socket_path()

            # Make sure the Unix domain socket doesn't exist before this call.
            try:
                os.unlink(buck_socket_path)
            except OSError as e:
                if e.errno == errno.ENOENT:
                    # Socket didn't previously exist.
                    pass
                else:
                    raise e

            process = subprocess.Popen(
                command,
                executable=which("java"),
                cwd=self._buck_project.root,
                close_fds=True,
                preexec_fn=preexec_func,
                env=self._environ_for_buck())

            self._buck_project.save_buckd_version(buck_version_uid)
            self._buck_project.update_buckd_run_count(0)

            # Give Java some time to create the listening socket.
            for i in range(0, 100):
                if not os.path.exists(buck_socket_path):
                    time.sleep(0.01)

            returncode = process.poll()

            # If the process hasn't exited yet, everything is working as expected
            if returncode is None:
                return 0

            return returncode

    def kill_buckd(self):
        with Tracing('BuckRepo.kill_buckd'):
            buckd_socket_path = self._buck_project.get_buckd_socket_path()
            if os.path.exists(buckd_socket_path):
                print("Shutting down nailgun server...", file=sys.stderr)
                try:
                    with NailgunConnection('local:.buckd/sock', cwd=self._buck_project.root) as c:
                        c.send_command('ng-stop')
                except NailgunException as e:
                    if e.code not in (NailgunException.CONNECT_FAILED,
                                      NailgunException.CONNECTION_BROKEN,
                                      NailgunException.UNEXPECTED_CHUNKTYPE):
                        raise BuckToolException(
                            'Unexpected error shutting down nailgun server: ' +
                            str(e))

            self._buck_project.clean_up_buckd()

    def _setup_watchman_watch(self):
        with Tracing('BuckRepo._setup_watchman_watch'):
            if not which('watchman'):
                message = textwrap.dedent("""\
                    Watchman not found, please install when using buckd.
                    See https://github.com/facebook/watchman for details.""")
                if sys.platform == "darwin":
                    message += "\n(brew install watchman on OS X)"
                # Bail if watchman isn't installed as we know java's
                # FileSystemWatcher will take too long to process events.
                raise BuckToolException(message)

            print("Using watchman.", file=sys.stderr)

    def _is_buckd_running(self):
        with Tracing('BuckRepo._is_buckd_running'):
            buckd_socket_path = self._buck_project.get_buckd_socket_path()

            if not os.path.exists(buckd_socket_path):
                return False

            try:
                with NailgunConnection(
                        'local:.buckd/sock',
                        stdin=None,
                        stdout=None,
                        stderr=None,
                        cwd=self._buck_project.root) as c:
                    c.send_command('ng-stats')
            except NailgunException as e:
                if e.code == NailgunException.CONNECT_FAILED:
                    return False
                else:
                    raise
            return True

    def _get_buck_version_uid(self):
        raise NotImplementedError()

    def _get_bootstrap_classpath(self):
        raise NotImplementedError()

    def _get_java_classpath(self):
        raise NotImplementedError()

    def _get_extra_java_args(self):
        return []

    def _get_java_args(self, version_uid, extra_default_options=[]):
        java_args = [] if is_java8() else ["-XX:MaxPermSize=256m"]
        java_args.extend([
            "-Xmx{0}m".format(JAVA_MAX_HEAP_SIZE_MB),
            "-Djava.awt.headless=true",
            "-Djava.util.logging.config.class=com.facebook.buck.cli.bootstrapper.LogConfig",
            "-Dbuck.test_util_no_tests_dir=true",
            "-Dbuck.version_uid={0}".format(version_uid),
            "-Dbuck.buckd_dir={0}".format(self._buck_project.buckd_dir),
            "-Dorg.eclipse.jetty.util.log.class=org.eclipse.jetty.util.log.JavaUtilLog",
        ])
        for resource in EXPORTED_RESOURCES:
            if self._has_resource(resource):
                java_args.append(
                    "-Dbuck.{0}={1}".format(
                    resource.name, self._get_resource(resource)))

        if sys.platform == "darwin":
            java_args.append("-Dbuck.enable_objc=true")
            java_args.append("-Djava.library.path=" + os.path.dirname(
                self._get_resource(
                    Resource("libjcocoa.dylib"))))

        if os.environ.get("BUCK_DEBUG_MODE"):
            java_args.append("-agentlib:jdwp=transport=dt_socket,"
                             "server=y,suspend=y,address=8888")

        if os.environ.get("BUCK_DEBUG_SOY"):
            java_args.append("-Dbuck.soy.debug=true")

        java_args.extend(extra_default_options)

        if self._buck_project.buck_javaargs:
            java_args.extend(shlex.split(self._buck_project.buck_javaargs))

        if self._buck_project.buck_javaargs_local:
            java_args.extend(shlex.split(self._buck_project.buck_javaargs_local))

        java_args.extend(self._get_extra_java_args())

        extra_java_args = os.environ.get("BUCK_EXTRA_JAVA_ARGS")
        if extra_java_args:
            java_args.extend(shlex.split(extra_java_args))
        return java_args

    def _platform_path(self, path):
        if sys.platform != 'cygwin':
            return path
        return subprocess.check_output(['cygpath', '-w', path]).strip()


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

_java8 = None

def is_java8():
    global _java8
    if _java8 is not None:
        return _java8
    try:
        cmd = ['java', '-Xms64m', '-version']
        output = check_output(cmd, stderr=subprocess.STDOUT)
        version_line = output.strip().splitlines()[0]
        m = re.compile('(openjdk|java) version "1\.8\..*').match(version_line)
        _java8 = bool(m)
        return _java8
    except CalledProcessError as e:
        print(e.output, file=sys.stderr)
        raise e


def install_signal_handlers():
    if os.name == 'posix':
        signal.signal(
            signal.SIGUSR1,
            lambda sig, frame: traceback.print_stack(frame))
