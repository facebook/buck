from __future__ import print_function

import errno
import glob
import json
import logging
import os
import platform
import shlex
import signal
import subprocess
import sys
import tempfile
import textwrap
import time
import traceback
import uuid
from subprocess import CalledProcessError, check_output

from pynailgun import NailgunConnection, NailgunException
from subprocutils import which
from timing import monotonic_time_nanos
from tracing import Tracing

BUCKD_CLIENT_TIMEOUT_MILLIS = 120000
BUCKD_STARTUP_TIMEOUT_MILLIS = 10000
GC_MAX_PAUSE_TARGET = 15000

JAVA_MAX_HEAP_SIZE_MB = 1000


class Resource(object):
    """Describes a resource used by this driver.

    :ivar name: logical name of the resources
    :ivar executable: whether the resource should/needs execute permissions
    :ivar basename: required basename of the resource
    """

    def __init__(self, name, executable=False, basename=None):
        self.name = name
        self.executable = executable
        self.basename = name if basename is None else basename


# Resource that get propagated to buck via system properties.
EXPORTED_RESOURCES = [
    Resource("fix_script", executable=True),
    Resource("testrunner_classes"),
    Resource("logging_config_file"),
    Resource("path_to_python_dsl"),
    Resource("path_to_pathlib_py", basename="pathlib.py"),
    Resource("path_to_pywatchman"),
    Resource("path_to_typing"),
    Resource("path_to_sh_binary_template"),
    Resource("path_to_isolated_trampoline"),
    Resource("jacoco_agent_jar"),
    Resource("report_generator_jar"),
    Resource("dx"),
    Resource("android_agent_path"),
    Resource("buck_build_type_info"),
    Resource("native_exopackage_fake_path"),
]


class CommandLineArgs:
    def __init__(self, cmdline):
        self.args = cmdline[1:]

        self.buck_options = []
        self.command = None
        self.command_options = []

        for arg in self.args:
            if self.command is not None:
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

    def is_version(self):
        return self.command is None and any(
            v in self.buck_options for v in ["--version", "-V"]
        )


class BuckToolException(Exception):
    pass


class BuckDaemonErrorException(BuckToolException):
    pass


class ExecuteTarget(Exception):
    def __init__(self, path, argv, envp, cwd):
        self._path = path
        self._argv = argv
        self._envp = envp
        self._cwd = cwd

    def execve(self):
        # Restore default handling of SIGPIPE.  See https://bugs.python.org/issue1652.
        if os.name != "nt":
            signal.signal(signal.SIGPIPE, signal.SIG_DFL)
            os.execvpe(self._path, self._argv, self._envp)
        else:
            child = subprocess.Popen(
                self._argv, executable=self._path, env=self._envp, cwd=self._cwd
            )
            child.wait()
            sys.exit(child.returncode)


class BuckStatusReporter(object):
    """ Add custom logic to log Buck completion statuses or errors including
    critical ones like JVM crashes and OOMs. This object is fully mutable with
    all fields optional which get populated on the go. Only safe operations
    are allowed in the reporter except for report() function which can throw
    """

    def __init__(self, argv):
        self.argv = argv
        self.build_id = None
        self.buck_version = None
        self.is_buckd = False
        self.no_buckd_reason = None
        self.status_message = None
        self.repository = None
        self.start_time = time.time()

    def report(self, exit_code):
        """ Add custom code here to track Buck invocations """
        pass


class JvmCrashLogger(object):
    def __init__(self, buck_tool, project_root):
        self._buck_tool = buck_tool
        self._project_root = project_root

    def __enter__(self):
        self.jvm_errors_before = self._get_jvm_errors()

    def __exit__(self, type, value, traceback):
        new_errors = self._get_jvm_errors() - self.jvm_errors_before
        if new_errors:
            self._log_jvm_errors(new_errors)

    def _get_jvm_errors(self):
        return set(glob.glob(os.path.join(self._project_root, "hs_err_pid*.log")))

    @staticmethod
    def _format_jvm_errors(fp):
        errors = []
        keep = False
        for line in fp:
            if line.startswith("Stack:"):
                keep = True
            if line.startswith("---------------  P R O C E S S  ---------------"):
                break
            if keep:
                errors.append(line)
        message = "JVM native crash: " + (errors[2] if len(errors) > 2 else "")
        return message, "".join(errors)

    def _log_jvm_errors(self, errors):
        for file in errors:
            with open(file, "r") as f:
                message, loglines = self._format_jvm_errors(f)
                logging.error(loglines)


class BuckTool(object):
    def __init__(self, buck_project, buck_reporter):
        self._reporter = buck_reporter
        self._package_info = self._get_package_info()
        self._init_timestamp = int(round(time.time() * 1000))
        self._command_line = CommandLineArgs(sys.argv)
        self._buck_project = buck_project
        self._tmp_dir = platform_path(buck_project.tmp_dir)
        self._fake_buck_version = os.environ.get("BUCK_FAKE_VERSION")
        if self._fake_buck_version:
            logging.info("Using fake buck version: {}".format(self._fake_buck_version))

        self._pathsep = os.pathsep
        if sys.platform == "cygwin":
            self._pathsep = ";"

    def _get_package_info(self):
        raise NotImplementedError()

    def _has_resource(self, resource):
        """Check whether the given resource exists."""
        raise NotImplementedError()

    def _get_resource(self, resource):
        """Return an on-disk path to the given resource.

        This may cause implementations to unpack the resource at this point.
        """
        raise NotImplementedError()

    def _get_resource_lock_path(self):
        """Return the path to the file used to determine if another buck process is using the same
        resources folder (used for cleanup coordination).
        """
        raise NotImplementedError()

    def _get_buck_version_uid(self):
        if self._fake_buck_version:
            return self._fake_buck_version
        return self._package_info["version"]

    def _get_buck_version_timestamp(self):
        return self._package_info["timestamp"]

    def _get_buck_git_commit(self):
        raise NotImplementedError()

    def _get_buck_repo_dirty(self):
        return self._package_info["is_dirty"]

    def _get_bootstrap_classpath(self):
        raise NotImplementedError()

    def _get_java_classpath(self):
        raise NotImplementedError()

    def _get_buck_binary_hash(self):
        """
        Returns Buck binary hash

        This hash reflects the code that can affect the content of artifacts.
        """

        raise NotImplementedError()

    def _unpack_modules(self):
        raise NotImplementedError()

    def _get_extra_java_args(self):
        return []

    def _get_exported_resources(self):
        return EXPORTED_RESOURCES

    @property
    def _use_buckd(self):
        return not os.environ.get("NO_BUCKD")

    def _environ_for_buck(self):
        env = os.environ.copy()
        env["CLASSPATH"] = str(self._get_bootstrap_classpath())
        env["BUCK_CLASSPATH"] = str(self._get_java_classpath())
        env["BUCK_TTY"] = str(int(sys.stdin.isatty()))
        if os.name == "posix":
            # It will fallback on default on Windows platform.
            try:
                # Get the number of columns in the terminal.
                with open(os.devnull, "w") as devnull:
                    stty_size_str = check_output(
                        ["stty", "size"], stderr=devnull
                    ).strip()
                    stty_size = stty_size_str.split(" ")
                    if len(stty_size) >= 2:
                        env["BUCK_TERM_COLUMNS"] = stty_size[1]
            except CalledProcessError:
                # If the call to tput fails, we use the default.
                pass
        return env

    def _add_args(self, argv, args):
        """
        Add new arguments to the beginning of arguments string
        Adding to the end will mess up with custom test runner params
        """
        if len(argv) < 2:
            return argv + args
        return [argv[0]] + args + argv[1:]

    def _add_args_from_env(self, argv):
        """
        Implicitly add command line arguments based on environmental variables. This is a bad
        practice and should be considered for infrastructure / debugging purposes only
        """

        if os.environ.get("BUCK_NO_CACHE") == "1" and "--no-cache" not in argv:
            argv = self._add_args(argv, ["--no-cache"])
        if os.environ.get("BUCK_CACHE_READONLY") == "1":
            argv = self._add_args(argv, ["-c", "cache.http_mode=readonly"])
        return argv

    def _run_with_nailgun(self, argv, env):
        """
        Run the command using nailgun.  If the daemon is busy, block until it becomes free.
        """
        exit_code = 2
        busy_diagnostic_displayed = False
        while exit_code == 2:
            try:
                with NailgunConnection(
                    self._buck_project.get_buckd_transport_address(),
                    cwd=self._buck_project.root,
                ) as c:
                    now = int(round(time.time() * 1000))
                    env["BUCK_PYTHON_SPACE_INIT_TIME"] = str(now - self._init_timestamp)
                    exit_code = c.send_command(
                        "com.facebook.buck.cli.Main",
                        self._add_args_from_env(argv),
                        env=env,
                        cwd=self._buck_project.root,
                    )
                    if exit_code == 2:
                        env["BUCK_BUILD_ID"] = str(uuid.uuid4())
                        if not busy_diagnostic_displayed:
                            logging.info(
                                "Buck daemon is busy with another command. "
                                + "Waiting for it to become free...\n"
                                + "You can use 'buck kill' to kill buck "
                                + "if you suspect buck is stuck."
                            )
                            busy_diagnostic_displayed = True
                        time.sleep(3)
            except NailgunException as nex:
                if nex.code == NailgunException.CONNECTION_BROKEN:
                    message = (
                        "Connection is lost to Buck daemon! This usually indicates that"
                        + " daemon experienced an unrecoverable error. Here is what you can do:\n"
                        + " - check if the machine has enough disk space and filesystem is"
                        + " accessible\n"
                        + " - check if the machine does not run out of physical memory\n"
                        + " - try to run Buck in serverless mode:"
                        + " buck kill && NO_BUCKD=1 buck <command>\n"
                    )
                    transport_address = self._buck_project.get_buckd_transport_address()
                    if not transport_address.startswith("local:"):
                        message += (
                            " - check if connection specified by "
                            + transport_address
                            + " is stable\n"
                        )

                    raise BuckDaemonErrorException(message)
                else:
                    raise nex

        return exit_code

    def _run_without_nailgun(self, argv, env):
        """
        Run the command by directly invoking `java` (rather than by sending a command via nailgun)
        """
        command = ["buck"]
        extra_default_options = [
            "-Djava.io.tmpdir={0}".format(self._tmp_dir),
            "-Dfile.encoding=UTF-8",
            "-XX:SoftRefLRUPolicyMSPerMB=0",
            "-XX:+UseG1GC",
        ]
        command.extend(
            self._get_java_args(self._get_buck_version_uid(), extra_default_options)
        )
        command.append("com.facebook.buck.cli.bootstrapper.ClassLoaderBootstrapper")
        command.append("com.facebook.buck.cli.Main")
        command.extend(self._add_args_from_env(argv))
        now = int(round(time.time() * 1000))
        env["BUCK_PYTHON_SPACE_INIT_TIME"] = str(now - self._init_timestamp)
        java = get_java_path()
        with Tracing("buck", args={"command": command}):
            return subprocess.call(
                command, cwd=self._buck_project.root, env=env, executable=java
            )

    def _execute_command_and_maybe_run_target(self, run_fn, env):
        """
        Run a buck command using the specified `run_fn`.  If the command is "run", get the path,
        args, etc. from the daemon, and raise an exception that tells __main__ to run that binary
        """
        with Tracing("buck", args={"command": sys.argv[1:]}):
            argv = sys.argv[1:]
            if len(argv) == 0 or argv[0] != "run":
                return run_fn(argv, env)
            else:
                with tempfile.NamedTemporaryFile(dir=self._tmp_dir) as argsfile:
                    # Splice in location of command file to run outside buckd
                    argv = [argv[0]] + ["--command-args-file", argsfile.name] + argv[1:]
                    exit_code = run_fn(argv, env)
                    if exit_code != 0 or os.path.getsize(argsfile.name) == 0:
                        # Build failed, so there's nothing to run.  Exit normally.
                        return exit_code
                    cmd = json.load(argsfile)
                    path = cmd["path"].encode("utf8")
                    argv = [arg.encode("utf8") for arg in cmd["argv"]]
                    envp = {
                        k.encode("utf8"): v.encode("utf8")
                        for k, v in cmd["envp"].iteritems()
                    }
                    cwd = cmd["cwd"].encode("utf8")
                    raise ExecuteTarget(path, argv, envp, cwd)

    def launch_buck(self, build_id):
        with Tracing("BuckTool.launch_buck"):
            with JvmCrashLogger(self, self._buck_project.root):
                self._reporter.build_id = build_id

                try:
                    repository = self._get_repository()
                    self._reporter.repository = repository
                except Exception as e:
                    # _get_repository() is only for reporting,
                    # so skip on error
                    logging.warning("Failed to get repo name: " + str(e))

                if (
                    self._command_line.command == "clean"
                    and not self._command_line.is_help()
                ):
                    self.kill_buckd()

                buck_version_uid = self._get_buck_version_uid()
                self._reporter.buck_version = buck_version_uid

                if self._command_line.is_version():
                    print("buck version {}".format(buck_version_uid))
                    return 0

                use_buckd = self._use_buckd
                if not use_buckd:
                    logging.warning("Not using buckd because NO_BUCKD is set.")
                    self._reporter.no_buckd_reason = "explicit"

                if use_buckd and self._command_line.is_help():
                    use_buckd = False
                    self._reporter.no_buckd_reason = "help"

                if use_buckd:
                    has_watchman = bool(which("watchman"))
                    if not has_watchman:
                        use_buckd = False
                        self._reporter.no_buckd_reason = "watchman"
                        logging.warning(
                            "Not using buckd because watchman isn't installed."
                        )

                if use_buckd:
                    need_start = True
                    running_version = self._buck_project.get_running_buckd_version()
                    if running_version is None:
                        logging.info("Starting new Buck daemon...")
                    elif running_version != buck_version_uid:
                        logging.info(
                            "Restarting Buck daemon because Buck version has changed..."
                        )
                    elif not self._is_buckd_running():
                        logging.info(
                            "Unable to connect to Buck daemon, restarting it..."
                        )
                    else:
                        need_start = False

                    if need_start:
                        self.kill_buckd()
                        if not self.launch_buckd(buck_version_uid=buck_version_uid):
                            use_buckd = False
                            self._reporter.no_buckd_reason = "daemon_failure"
                            logging.warning(
                                "Not using buckd because daemon failed to start."
                            )

                env = self._environ_for_buck()
                env["BUCK_BUILD_ID"] = build_id

                self._reporter.is_buckd = use_buckd
                run_fn = (
                    self._run_with_nailgun if use_buckd else self._run_without_nailgun
                )

                self._unpack_modules()

                exit_code = self._execute_command_and_maybe_run_target(run_fn, env)

                # Most shells return process termination with signal as
                # 128 + N, where N is the signal. However Python's subprocess
                # call returns them as negative numbers. Buck binary protocol
                # uses shell's convention, so convert
                if exit_code < 0:
                    exit_code = 128 + (-1 * exit_code)
                return exit_code


    def launch_buckd(self, buck_version_uid=None):
        with Tracing("BuckTool.launch_buckd"):
            setup_watchman_watch()
            if buck_version_uid is None:
                buck_version_uid = self._get_buck_version_uid()
            # Override self._tmp_dir to a long lived directory.
            buckd_tmp_dir = self._buck_project.create_buckd_tmp_dir()
            ngserver_output_path = os.path.join(buckd_tmp_dir, "ngserver-out")

            """
            Use SoftRefLRUPolicyMSPerMB for immediate GC of javac output.
            Set timeout to 60s (longer than the biggest GC pause seen for a 2GB
            heap) and GC target to 15s. This means that the GC has to miss its
            target by 100% or many 500ms heartbeats must be missed before a client
            disconnection occurs. Specify port 0 to allow Nailgun to find an
            available port, then parse the port number out of the first log entry.
            """
            command = ["buckd"]
            extra_default_options = [
                "-Dbuck.buckd_launch_time_nanos={0}".format(monotonic_time_nanos()),
                "-Dfile.encoding=UTF-8",
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
                    ngserver_output_path
                ),
                "-XX:+UseG1GC",
                "-XX:MaxHeapFreeRatio=40",
            ]

            command.extend(self._get_java_args(buck_version_uid, extra_default_options))
            command.append("com.facebook.buck.cli.bootstrapper.ClassLoaderBootstrapper")
            command.append("com.facebook.buck.cli.Main$DaemonBootstrap")
            command.append(self._buck_project.get_buckd_transport_address())
            command.append("{0}".format(BUCKD_CLIENT_TIMEOUT_MILLIS))

            buckd_transport_file_path = (
                self._buck_project.get_buckd_transport_file_path()
            )
            if os.name == "nt":
                # https://msdn.microsoft.com/en-us/library/windows/desktop/ms684863.aspx#DETACHED_PROCESS
                DETACHED_PROCESS = 0x00000008
                creationflags = DETACHED_PROCESS
                # do not redirect output for Windows as it deadlocks
                stdin = None
                stdout = None
                stderr = None
                close_fds = True
            else:
                """
                Change the process group of the child buckd process so that when this
                script is interrupted, it does not kill buckd.
                """
                creationflags = 0
                stdin = open(os.devnull, mode="r")
                stdout = open(
                    self._buck_project.get_buckd_stdout(), mode="w+b", buffering=0
                )
                stderr = open(
                    self._buck_project.get_buckd_stderr(), mode="w+b", buffering=0
                )
                close_fds = False

            process = subprocess.Popen(
                command,
                executable=get_java_path(),
                cwd=self._buck_project.root,
                env=self._environ_for_buck(),
                creationflags=creationflags,
                close_fds=close_fds,
                stdin=stdin,
                stdout=stdout,
                stderr=stderr,
            )

            self._buck_project.save_buckd_version(buck_version_uid)

            # Give Java some time to create the listening socket.

            wait_seconds = 0.01
            repetitions = int(BUCKD_STARTUP_TIMEOUT_MILLIS / 1000.0 / wait_seconds)
            for i in range(repetitions):
                if transport_exists(buckd_transport_file_path):
                    break
                time.sleep(wait_seconds)

            if not transport_exists(buckd_transport_file_path):
                return False

            returncode = process.poll()

            # If the process has exited then daemon failed to start
            if returncode is not None:
                return False

            # Save pid of running daemon
            self._buck_project.save_buckd_pid(process.pid)

            return True

    def _get_repository(self):
        arcconfig = os.path.join(self._buck_project.root, ".arcconfig")
        if os.path.isfile(arcconfig):
            with open(arcconfig, "r") as fp:
                return json.load(fp).get("project_id", None)
        return os.path.basename(self._buck_project.root)


    def kill_buckd(self):
        with Tracing("BuckTool.kill_buckd"):
            buckd_transport_file_path = (
                self._buck_project.get_buckd_transport_file_path()
            )
            if transport_exists(buckd_transport_file_path):
                buckd_pid = self._buck_project.get_running_buckd_pid()
                logging.debug("Shutting down buck daemon.")
                wait_socket_close = False
                try:
                    with NailgunConnection(
                        self._buck_project.get_buckd_transport_address(),
                        cwd=self._buck_project.root,
                    ) as c:
                        c.send_command("ng-stop")
                    wait_socket_close = True
                except NailgunException as e:
                    if e.code not in (
                        NailgunException.CONNECT_FAILED,
                        NailgunException.CONNECTION_BROKEN,
                        NailgunException.UNEXPECTED_CHUNKTYPE,
                    ):
                        raise BuckToolException(
                            "Unexpected error shutting down nailgun server: " + str(e)
                        )

                # If ng-stop command succeeds, wait for buckd process to terminate and for the
                # socket to close. On Unix ng-stop always drops the connection and throws.
                if wait_socket_close:
                    for i in range(0, 300):
                        if not transport_exists(buckd_transport_file_path):
                            break
                        time.sleep(0.01)
                elif buckd_pid is not None and os.name == "posix":
                    # otherwise just wait for up to 5 secs for the process to die
                    # TODO(buck_team) implement wait for process and hard kill for Windows too
                    if not wait_for_process_posix(buckd_pid, 5000):
                        # There is a possibility that daemon is dead for some time but pid file
                        # still exists and another process is assigned to the same pid. Ideally we
                        # should check first which process we are killing but so far let's pretend
                        # this will never happen and kill it with fire anyways.

                        try:
                            force_kill_process_posix(buckd_pid)
                        except Exception as e:
                            # In the worst case it keeps running multiple daemons simultaneously
                            # consuming memory. Not the good place to be, but let's just issue a
                            # warning for now.
                            logging.warning(
                                "Error killing running Buck daemon " + str(e)
                            )

                if transport_exists(buckd_transport_file_path):
                    force_close_transport(buckd_transport_file_path)

            self._buck_project.clean_up_buckd()

    def _is_buckd_running(self):
        with Tracing("BuckTool._is_buckd_running"):
            transport_file_path = self._buck_project.get_buckd_transport_file_path()

            if not transport_exists(transport_file_path):
                return False

            try:
                with NailgunConnection(
                    self._buck_project.get_buckd_transport_address(),
                    stdin=None,
                    stdout=None,
                    stderr=None,
                    cwd=self._buck_project.root,
                ) as c:
                    c.send_command("ng-stats")
            except NailgunException as e:
                if e.code in (
                    NailgunException.CONNECT_FAILED,
                    NailgunException.CONNECTION_BROKEN,
                ):
                    return False
                else:
                    raise
            return True

    def _get_java_args(self, version_uid, extra_default_options=None):
        with Tracing("BuckTool._get_java_args"):
            java_args = [
                "-Xmx{0}m".format(JAVA_MAX_HEAP_SIZE_MB),
                "-Djava.awt.headless=true",
                "-Djna.nosys=true",
                "-Djava.util.logging.config.class=com.facebook.buck.cli.bootstrapper.LogConfig",
                "-Dbuck.test_util_no_tests_dir=true",
                "-Dbuck.version_uid={0}".format(version_uid),
                "-Dbuck.buckd_dir={0}".format(self._buck_project.buckd_dir),
                "-Dorg.eclipse.jetty.util.log.class=org.eclipse.jetty.util.log.JavaUtilLog",
                "-Dbuck.git_commit={0}".format(self._get_buck_version_uid()),
                "-Dbuck.git_commit_timestamp={0}".format(
                    self._get_buck_version_timestamp()
                ),
                "-Dbuck.binary_hash={0}".format(self._get_buck_binary_hash()),
            ]

            if "BUCK_DEFAULT_FILESYSTEM" not in os.environ and (
                sys.platform == "darwin" or sys.platform.startswith("linux")
            ):
                # Change default filesystem to custom filesystem for memory optimizations
                # Calls like Paths.get() would return optimized Path implementation
                java_args.append(
                    "-Djava.nio.file.spi.DefaultFileSystemProvider="
                    "com.facebook.buck.cli.bootstrapper.filesystem.BuckFileSystemProvider"
                )

            resource_lock_path = self._get_resource_lock_path()
            if resource_lock_path is not None:
                java_args.append(
                    "-Dbuck.resource_lock_path={0}".format(resource_lock_path)
                )

            for resource in self._get_exported_resources():
                if self._has_resource(resource):
                    java_args.append(
                        "-Dbuck.{0}={1}".format(
                            resource.name, self._get_resource(resource)
                        )
                    )

            if sys.platform == "darwin":
                java_args.append("-Dbuck.enable_objc=true")
                java_args.append(
                    "-Djava.library.path="
                    + os.path.dirname(self._get_resource(Resource("libjcocoa.dylib")))
                )

            if (
                "BUCK_DEBUG_MODE" in os.environ
                and os.environ.get("BUCK_DEBUG_MODE") != "0"
            ):
                suspend = "n" if os.environ.get("BUCK_DEBUG_MODE") == "2" else "y"
                java_args.append(
                    "-agentlib:jdwp=transport=dt_socket,"
                    "server=y,suspend=" + suspend + ",quiet=y,address=8888"
                )

            if (
                "BUCK_HTTP_PORT" in os.environ
                and os.environ.get("BUCK_HTTP_PORT").lstrip("+-").isdigit()
            ):
                java_args.append(
                    "-Dbuck.httpserver.port=" + os.environ.get("BUCK_HTTP_PORT")
                )

            if os.environ.get("BUCK_DEBUG_SOY"):
                java_args.append("-Dbuck.soy.debug=true")

            java_args.extend(extra_default_options or [])

            if self._buck_project.buck_javaargs:
                java_args.extend(shlex.split(self._buck_project.buck_javaargs))

            if self._buck_project.buck_javaargs_local:
                java_args.extend(shlex.split(self._buck_project.buck_javaargs_local))

            java_args.extend(self._get_extra_java_args())

            extra_java_args = os.environ.get("BUCK_EXTRA_JAVA_ARGS")
            if extra_java_args:
                java_args.extend(shlex.split(extra_java_args))
            return java_args




def install_signal_handlers():
    if os.name == "posix":
        signal.signal(signal.SIGUSR1, lambda sig, frame: traceback.print_stack(frame))


def _get_java_exec_under_home(java_home_base):
    java_exec = "java.exe" if os.name == "nt" else "java"
    return os.path.join(java_home_base, "bin", java_exec)


def get_java_path():
    java_home_path = os.getenv("JAVA_HOME")
    if java_home_path is None:
        java_path = which("java")
        if java_path is None:
            raise BuckToolException(
                "Could not find Java executable. \
Make sure it is on PATH or JAVA_HOME is set."
            )
    else:
        java_path = _get_java_exec_under_home(java_home_path)
        if not os.path.isfile(java_path):
            message = textwrap.dedent(
                """
            Could not find Java executable under JAVA_HOME at: '{}'.
            Please make sure your JAVA_HOME environment variable is set correctly.
            """
            ).format(java_path)
            raise BuckToolException(message)
    return java_path


def platform_path(path):
    if sys.platform != "cygwin":
        return path
    return check_output(["cygpath", "-w", path]).strip()


def setup_watchman_watch():
    with Tracing("BuckTool._setup_watchman_watch"):
        if not which("watchman"):
            message = textwrap.dedent(
                """\
                Watchman not found, please install when using buckd.
                See https://github.com/facebook/watchman for details."""
            )
            if sys.platform == "darwin":
                message += "\n(brew install watchman on OS X)"
            # Bail if watchman isn't installed as we know java's
            # FileSystemWatcher will take too long to process events.
            raise BuckToolException(message)

        logging.debug("Using watchman.")


def force_close_transport(path):
    if os.name == "nt":
        # TODO(buck_team): do something for Windows too
        return

    try:
        os.unlink(path)
    except OSError as e:
        # it is ok if socket did not exist
        if e.errno != errno.ENOENT:
            raise e


def transport_exists(path):
    return os.path.exists(path)


if os.name == "nt":
    import ctypes
    from ctypes.wintypes import WIN32_FIND_DATAW as WIN32_FIND_DATA

    INVALID_HANDLE_VALUE = -1
    FindFirstFile = ctypes.windll.kernel32.FindFirstFileW
    FindClose = ctypes.windll.kernel32.FindClose

    # on windows os.path.exists doesn't allow to check reliably that pipe exists
    # (os.path.exists tries to open connection to a pipe)
    def transport_exists(transport_path):
        wfd = WIN32_FIND_DATA()
        handle = FindFirstFile(transport_path, ctypes.byref(wfd))
        result = handle != INVALID_HANDLE_VALUE
        FindClose(handle)
        return result


def pid_exists_posix(pid):
    """
    Check whether process with given pid exists, does not work on Windows
    """
    if pid <= 0:
        return False

    try:
        # does not actually kill the process
        os.kill(pid, 0)
    except OSError as err:
        if err.errno == errno.ESRCH:
            # No such process
            return False
        elif err.errno == errno.EPERM:
            # Access denied which means process still exists
            return True
        raise

    return True


def wait_for_process_posix(pid, timeout):
    """
    Wait for the process with given id to finish, up to timeout in milliseconds
    Return True if process has finished, False otherwise
    """

    logging.debug(
        "Waiting for process (pid="
        + str(pid)
        + ") to terminate for "
        + str(timeout)
        + " ms..."
    )

    # poll 10 times a second
    for i in range(0, int(timeout / 100)):
        if not pid_exists_posix(pid):
            return True
        time.sleep(0.1)

    return False


def force_kill_process_posix(pid):
    """
    Hard kill the process using process id
    """
    logging.debug("Sending SIGTERM to process (pid=" + str(pid) + ")")
    os.kill(pid, signal.SIGTERM)

    # allow up to 5 seconds for the process to react to termination signal, then shoot it
    # in the head
    if not wait_for_process_posix(pid, 5000):
        logging.debug("Sending SIGKILL to process (pid=" + str(pid) + ")")
        os.kill(pid, signal.SIGKILL)
