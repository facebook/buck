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

MAX_BUCKD_RUN_COUNT = 64
BUCKD_CLIENT_TIMEOUT_MILLIS = 60000
GC_MAX_PAUSE_TARGET = 15000

BUCKD_LOG_FILE_PATTERN = re.compile('^NGServer.* port (\d+)\.$')
NAILGUN_CONNECTION_REFUSED_CODE = 230
# TODO(natthu): CI servers, for some reason, encounter this error.
# For now, simply skip this error. Need to figure out why this happens.
NAILGUN_UNEXPECTED_CHUNK_TYPE = 229
NAILGUN_CONNECTION_BROKEN_CODE = 227


# Describes a resource used by this driver.
#  - name: logical name of the resources
#  - executable: whether the resource should/needs execute permissions
#  - basename: required basename of the resource
class Resource(object):
    def __init__(self, name, executable=False, basename=None):
        self.name = name
        self.executable = executable
        self.basename = name if basename is None else basename


CLIENT = Resource("buck_client", executable=True, basename='ng')
LOG4J_CONFIG = Resource("log4j_config_file")

# Resource that get propagated to buck via system properties.
EXPORTED_RESOURCES = [
    Resource("testrunner_classes"),
    Resource("abi_processor_classes"),
    Resource("path_to_asm_jar"),
    Resource("logging_config_file"),
    Resource("path_to_buck_py"),
    Resource("path_to_pathlib_py", basename='pathlib.py'),
    Resource("path_to_compile_asset_catalogs_py"),
    Resource("path_to_compile_asset_catalogs_build_phase_sh"),
    Resource("path_to_intellij_py"),
    Resource("path_to_python_test_main"),
    Resource("jacoco_agent_jar"),
    Resource("report_generator_jar"),
    Resource("path_to_static_content"),
    Resource("path_to_pex", executable=True),
    Resource("quickstart_origin_dir"),
    Resource("dx"),
    Resource("android_agent_path"),
]


class RestartBuck(Exception):
    pass


class BuckToolException(Exception):
    pass


class BuckTool(object):

    def __init__(self, buck_project):
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
        return not os.environ.get('NO_BUCKD')

    def _environ_for_buck(self):
        env = os.environ.copy()
        env['CLASSPATH'] = self._get_bootstrap_classpath()
        env['BUCK_CLASSPATH'] = self._get_java_classpath()
        return env

    def launch_buck(self, build_id):
        with Tracing('BuckRepo.launch_buck'):
            self.kill_autobuild()
            if 'clean' in sys.argv:
                self.kill_buckd()

            buck_version_uid = self._get_buck_version_uid()

            use_buckd = self._use_buckd()
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

            env = self._environ_for_buck()
            env['BUCK_BUILD_ID'] = build_id

            buck_client_file = self._get_resource(CLIENT)
            if use_buckd and self._is_buckd_running() and os.path.exists(buck_client_file):
                print("Using buckd.", file=sys.stderr)
                buckd_port = self._buck_project.get_buckd_port()
                if not buckd_port or not buckd_port.isdigit():
                    print(
                        "Daemon port file is corrupt, starting new buck process.",
                        file=sys.stderr)
                    self.kill_buckd()
                else:
                    command = [buck_client_file]
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

            command = ["buck"]
            command.extend(self._get_java_args(buck_version_uid))
            command.append("-Djava.io.tmpdir={0}".format(self._tmp_dir))
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
            command.extend(self._get_java_args(buck_version_uid))
            command.append("-Dbuck.buckd_launch_time_nanos={0}".format(monotonic_time_nanos()))
            command.append("-Dbuck.buckd_watcher=Watchman")
            command.append("-XX:MaxGCPauseMillis={0}".format(GC_MAX_PAUSE_TARGET))
            command.append("-XX:SoftRefLRUPolicyMSPerMB=0")
            command.append("-Djava.io.tmpdir={0}".format(buckd_tmp_dir))
            command.append("-Dcom.martiansoftware.nailgun.NGServer.outputPath={0}".format(
                ngserver_output_path))
            command.append("com.facebook.buck.cli.bootstrapper.ClassLoaderBootstrapper")
            command.append("com.martiansoftware.nailgun.NGServer")
            command.append("localhost:0")
            command.append("{0}".format(BUCKD_CLIENT_TIMEOUT_MILLIS))

            '''
            Change the process group of the child buckd process so that when this
            script is interrupted, it does not kill buckd.
            '''
            def preexec_func():
                # Close any open file descriptors to further separate buckd from its
                # invoking context (e.g. otherwise we'd hang when running things like
                # `ssh localhost buck clean`).

                # N.B. preexec_func is POSIX-only, and any reasonable
                # POSIX system has a /dev/null
                os.setpgrp()
                dev_null_fd = os.open("/dev/null", os.O_RDWR)
                os.dup2(dev_null_fd, 0)
                os.dup2(dev_null_fd, 1)
                os.dup2(dev_null_fd, 2)
                os.close(dev_null_fd)

            process = subprocess.Popen(
                command,
                executable=which("java"),
                cwd=self._buck_project.root,
                close_fds=True,
                preexec_fn=preexec_func,
                env=self._environ_for_buck())

            buckd_port = None
            for i in range(100):
                if buckd_port:
                    break
                try:
                    with open(ngserver_output_path) as f:
                        for line in f:
                            match = BUCKD_LOG_FILE_PATTERN.match(line)
                            if match:
                                buckd_port = match.group(1)
                                break
                except IOError as e:
                    pass
                finally:
                    time.sleep(0.1)
            else:
                print(
                    "nailgun server did not respond after 10s. Aborting buckd.",
                    file=sys.stderr)
                return

            self._buck_project.save_buckd_port(buckd_port)
            self._buck_project.save_buckd_version(buck_version_uid)
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
                    buck_client_file = self._get_resource(CLIENT)
                    command = [buck_client_file]
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
                raise BuckToolException(message)

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

            buck_client_file = self._get_resource(CLIENT)
            command = [buck_client_file]
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

    def _get_buck_version_uid(self):
        raise NotImplementedError()

    def _get_bootstrap_classpath(self):
        raise NotImplementedError()

    def _get_java_classpath(self):
        raise NotImplementedError()

    def _get_extra_java_args(self):
        return []

    def _get_java_args(self, version_uid):
        java_args = [] if is_java8() else ["-XX:MaxPermSize=256m"]
        java_args.extend([
            "-Xmx1000m",
            "-Djava.awt.headless=true",
            "-Djava.util.logging.config.class=com.facebook.buck.cli.bootstrapper.LogConfig",
            "-Dbuck.test_util_no_tests_dir=true",
            "-Dbuck.version_uid={0}".format(version_uid),
            "-Dbuck.buckd_dir={0}".format(self._buck_project.buckd_dir),
            "-Dlog4j.configuration=file:{0}".format(
                self._get_resource(LOG4J_CONFIG)),
        ])
        for resource in EXPORTED_RESOURCES:
            if self._has_resource(resource):
                java_args.append(
                    "-Dbuck.{0}={1}".format(
                    resource.name, self._get_resource(resource)))

        if os.environ.get("BUCK_DEBUG_MODE"):
            java_args.append("-agentlib:jdwp=transport=dt_socket,"
                             "server=y,suspend=y,address=8888")

        if os.environ.get("BUCK_DEBUG_SOY"):
            java_args.append("-Dbuck.soy.debug=true")

        if self._buck_project.buck_javaargs:
            java_args.extend(self._buck_project.buck_javaargs.split(' '))

        java_args.extend(self._get_extra_java_args())

        extra_java_args = os.environ.get("BUCK_EXTRA_JAVA_ARGS")
        if extra_java_args:
            java_args.extend(extra_java_args.split(' '))
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
    output = check_output(['java', '-version'], stderr=subprocess.STDOUT)
    version_line = output.strip().splitlines()[0]
    return re.compile('java version "1\.8\..*').match(version_line)
