#!/usr/bin/env python
#
# Copyright 2004-2015, Martian Software, Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import ctypes
import platform
import optparse
import os
import os.path
import Queue
import select
import socket
import struct
import sys
from threading import Condition, Event, Thread

# @author <a href="http://www.martiansoftware.com/contact.html">Marty Lamb</a>
# @author Pete Kirkham (Win32 port)
# @author Ben Hamilton (Python port)
#
# Please try to keep this working on Python 2.6.

NAILGUN_VERSION = '0.9.0'
BUFSIZE = 2048
NAILGUN_PORT_DEFAULT = 2113
CHUNK_HEADER_LEN = 5

CHUNKTYPE_STDIN = '0'
CHUNKTYPE_STDOUT = '1'
CHUNKTYPE_STDERR = '2'
CHUNKTYPE_STDIN_EOF = '.'
CHUNKTYPE_ARG = 'A'
CHUNKTYPE_LONGARG = 'L'
CHUNKTYPE_ENV = 'E'
CHUNKTYPE_DIR = 'D'
CHUNKTYPE_CMD = 'C'
CHUNKTYPE_EXIT = 'X'
CHUNKTYPE_SENDINPUT = 'S'
CHUNKTYPE_HEARTBEAT = 'H'

NSEC_PER_SEC = 1000000000

# 500 ms heartbeat timeout
HEARTBEAT_TIMEOUT_NANOS = NSEC_PER_SEC / 2
HEARTBEAT_TIMEOUT_SECS = HEARTBEAT_TIMEOUT_NANOS / (NSEC_PER_SEC * 1.0)

# We need to support Python 2.6 hosts which lack memoryview().
import __builtin__
HAS_MEMORYVIEW = 'memoryview' in dir(__builtin__)

EVENT_STDIN_CHUNK = 0
EVENT_STDIN_CLOSED = 1
EVENT_STDIN_EXCEPTION = 2

class NailgunException(Exception):
    SOCKET_FAILED = 231
    CONNECT_FAILED = 230
    UNEXPECTED_CHUNKTYPE = 229
    CONNECTION_BROKEN = 227

    def __init__(self, message, code):
        self.message = message
        self.code = code

    def __str__(self):
        return self.message


class NailgunConnection(object):
    '''Stateful object holding the connection to the Nailgun server.'''

    def __init__(
            self,
            server_name,
            server_port=None,
            stdin=sys.stdin,
            stdout=sys.stdout,
            stderr=sys.stderr):
        self.socket = make_nailgun_socket(server_name, server_port)
        self.stdin = stdin
        self.stdout = stdout
        self.stderr = stderr
        self.recv_flags = 0
        self.send_flags = 0
        if hasattr(socket, 'MSG_WAITALL'):
            self.recv_flags |= socket.MSG_WAITALL
        if hasattr(socket, 'MSG_NOSIGNAL'):
            self.send_flags |= socket.MSG_NOSIGNAL
        self.header_buf = ctypes.create_string_buffer(CHUNK_HEADER_LEN)
        self.buf = ctypes.create_string_buffer(BUFSIZE)
        self.ready_to_send_condition = Condition()
        self.sendtime_nanos = 0
        self.exit_code = None
        self.stdin_queue = Queue.Queue()
        self.shutdown_event = Event()
        self.stdin_thread = Thread(
            target=stdin_thread_main,
            args=(self.stdin, self.stdin_queue, self.shutdown_event, self.ready_to_send_condition))
        self.stdin_thread.daemon = True

    def send_command(
            self,
            cmd,
            cmd_args=[],
            filearg=None,
            env=os.environ,
            cwd=os.getcwd()):
        '''
        Sends the command and environment to the nailgun server.
        '''
        if filearg:
            send_file_arg(filearg, self)
        for cmd_arg in cmd_args:
            send_chunk(cmd_arg, CHUNKTYPE_ARG, self)
        send_env_var('NAILGUN_FILESEPARATOR', os.sep, self)
        send_env_var('NAILGUN_PATHSEPARATOR', os.pathsep, self)
        send_tty_format(self.stdin, self)
        send_tty_format(self.stdout, self)
        send_tty_format(self.stderr, self)
        for k, v in env.iteritems():
            send_env_var(k, v, self)
        send_chunk(cwd, CHUNKTYPE_DIR, self)
        send_chunk(cmd, CHUNKTYPE_CMD, self)
        self.stdin_thread.start()
        while self.exit_code is None:
            self._process_next_chunk()
            self._check_stdin_queue()
        self.shutdown_event.set()
        with self.ready_to_send_condition:
            self.ready_to_send_condition.notify()
        # We can't really join on self.stdin_thread, since
        # there's no way to interrupt its call to sys.stdin.readline.
        return self.exit_code

    def _process_next_chunk(self):
        '''
        Processes the next chunk from the nailgun server.
        '''
        select_list = set([self.socket])
        readable, _, exceptional = select.select(
            select_list, [], select_list, HEARTBEAT_TIMEOUT_SECS)
        if self.socket in readable:
            process_nailgun_stream(self)
        now = monotonic_time_nanos()
        if now - self.sendtime_nanos > HEARTBEAT_TIMEOUT_NANOS:
            send_heartbeat(self)
        if self.socket in exceptional:
            raise NailgunException(
                'Server disconnected in select',
                NailgunException.CONNECTION_BROKEN)

    def _check_stdin_queue(self):
        '''Check if the stdin thread has read anything.'''
        while not self.stdin_queue.empty():
            try:
                (event_type, event_arg) = self.stdin_queue.get_nowait()
                if event_type == EVENT_STDIN_CHUNK:
                    send_chunk(event_arg, CHUNKTYPE_STDIN, self)
                elif event_type == EVENT_STDIN_CLOSED:
                    send_chunk('', CHUNKTYPE_STDIN_EOF, self)
                elif event_type == EVENT_STDIN_EXCEPTION:
                    raise event_arg
            except Queue.Empty:
                break

    def __enter__(self):
        return self

    def __exit__(self, type, value, traceback):
        self.socket.close()


def monotonic_time_nanos():
    '''Returns a monotonically-increasing timestamp value in nanoseconds.

    The epoch of the return value is undefined. To use this, you must call
    it more than once and calculate the delta between two calls.
    '''
    # This function should be overwritten below on supported platforms.
    raise Exception('Unsupported platform: ' + platform.system())


if platform.system() == 'Linux':
    # From <linux/time.h>, available since 2.6.28 (released 24-Dec-2008).
    CLOCK_MONOTONIC_RAW = 4
    librt = ctypes.CDLL('librt.so.1', use_errno=True)
    clock_gettime = librt.clock_gettime

    class struct_timespec(ctypes.Structure):
        _fields_ = [('tv_sec', ctypes.c_long), ('tv_nsec', ctypes.c_long)]
    clock_gettime.argtypes = [ctypes.c_int, ctypes.POINTER(struct_timespec)]

    def _monotonic_time_nanos_linux():
        t = struct_timespec()
        clock_gettime(CLOCK_MONOTONIC_RAW, ctypes.byref(t))
        return t.tv_sec * NSEC_PER_SEC + t.tv_nsec
    monotonic_time_nanos = _monotonic_time_nanos_linux
elif platform.system() == 'Darwin':
    # From <mach/mach_time.h>
    KERN_SUCCESS = 0
    libSystem = ctypes.CDLL('/usr/lib/libSystem.dylib', use_errno=True)
    mach_timebase_info = libSystem.mach_timebase_info

    class struct_mach_timebase_info(ctypes.Structure):
        _fields_ = [('numer', ctypes.c_uint32), ('denom', ctypes.c_uint32)]
    mach_timebase_info.argtypes = [ctypes.POINTER(struct_mach_timebase_info)]
    mach_ti = struct_mach_timebase_info()
    ret = mach_timebase_info(ctypes.byref(mach_ti))
    if ret != KERN_SUCCESS:
        raise Exception('Could not get mach_timebase_info, error: ' + str(ret))
    mach_absolute_time = libSystem.mach_absolute_time
    mach_absolute_time.restype = ctypes.c_uint64

    def _monotonic_time_nanos_darwin():
        return (mach_absolute_time() * mach_ti.numer) / mach_ti.denom
    monotonic_time_nanos = _monotonic_time_nanos_darwin
elif platform.system() == 'Windows':
    # From <Winbase.h>
    perf_frequency = ctypes.c_uint64()
    ctypes.windll.kernel32.QueryPerformanceFrequency(ctypes.byref(perf_frequency))

    def _monotonic_time_nanos_windows():
        perf_counter = ctypes.c_uint64()
        ctypes.windll.kernel32.QueryPerformanceCounter(ctypes.byref(perf_counter))
        return perf_counter.value * NSEC_PER_SEC / perf_frequency.value
    monotonic_time_nanos = _monotonic_time_nanos_windows
elif sys.platform == 'cygwin':
    k32 = ctypes.CDLL('Kernel32', use_errno=True)
    perf_frequency = ctypes.c_uint64()
    k32.QueryPerformanceFrequency(ctypes.byref(perf_frequency))

    def _monotonic_time_nanos_cygwin():
        perf_counter = ctypes.c_uint64()
        k32.QueryPerformanceCounter(ctypes.byref(perf_counter))
        return perf_counter.value * NSEC_PER_SEC / perf_frequency.value
    monotonic_time_nanos = _monotonic_time_nanos_cygwin


def send_chunk(buf, chunk_type, nailgun_connection):
    '''
    Sends a chunk noting the specified payload size and chunk type.
    '''
    struct.pack_into('>ic', nailgun_connection.header_buf, 0, len(buf), chunk_type)
    nailgun_connection.sendtime_nanos = monotonic_time_nanos()
    nailgun_connection.socket.sendall(
        nailgun_connection.header_buf.raw,
        nailgun_connection.send_flags)
    nailgun_connection.socket.sendall(buf, nailgun_connection.send_flags)


def send_env_var(name, value, nailgun_connection):
    '''
    Sends an environment variable in KEY=VALUE format.
    '''
    send_chunk('='.join((name, value)), CHUNKTYPE_ENV, nailgun_connection)


def send_tty_format(f, nailgun_connection):
    '''
    Sends a NAILGUN_TTY_# environment variable.
    '''
    if not f or not hasattr(f, 'fileno'):
        return
    fileno = f.fileno()
    isatty = os.isatty(fileno)
    send_env_var('NAILGUN_TTY_' + str(fileno), str(int(isatty)), nailgun_connection)


def send_file_arg(filename, nailgun_connection):
    '''
    Sends the contents of a file to the server.
    '''
    with open(filename) as f:
        while True:
            num_bytes = f.readinto(nailgun_connection.buf)
            if not num_bytes:
                break
            send_chunk(
                nailgun_connection.buf.raw[:num_bytes], CHUNKTYPE_LONGARG, nailgun_connection)


def recv_to_fd(dest_file, num_bytes, nailgun_connection):
    '''
    Receives num_bytes bytes from the nailgun socket and copies them to the specified file
    object. Used to route data to stdout or stderr on the client.
    '''
    bytes_read = 0

    while bytes_read < num_bytes:
        bytes_to_read = min(len(nailgun_connection.buf), num_bytes - bytes_read)
        bytes_received = nailgun_connection.socket.recv_into(
            nailgun_connection.buf,
            bytes_to_read,
            nailgun_connection.recv_flags)
        if dest_file:
            dest_file.write(nailgun_connection.buf[:bytes_received])
        bytes_read += bytes_received


def recv_to_buffer(num_bytes, buf, nailgun_connection):
    '''
    Receives num_bytes from the nailgun socket and writes them into the specified buffer.
    '''
    # We'd love to use socket.recv_into() everywhere to avoid
    # unnecessary copies, but we need to support Python 2.6. The
    # only way to provide an offset to recv_into() is to use
    # memoryview(), which doesn't exist until Python 2.7.
    if HAS_MEMORYVIEW:
        recv_into_memoryview(num_bytes, memoryview(buf), nailgun_connection)
    else:
        recv_to_buffer_with_copy(num_bytes, buf, nailgun_connection)


def recv_into_memoryview(num_bytes, buf_view, nailgun_connection):
    '''
    Receives num_bytes from the nailgun socket and writes them into the specified memoryview
    to avoid an extra copy.
    '''
    bytes_read = 0
    while bytes_read < num_bytes:
        bytes_received = nailgun_connection.socket.recv_into(
            buf_view[bytes_read:],
            num_bytes - bytes_read,
            nailgun_connection.recv_flags)
        if not bytes_received:
            raise NailgunException(
                'Server unexpectedly disconnected in recv_into()',
                NailgunException.CONNECTION_BROKEN)
        bytes_read += bytes_received


def recv_to_buffer_with_copy(num_bytes, buf, nailgun_connection):
    '''
    Receives num_bytes from the nailgun socket and writes them into the specified buffer.
    '''
    bytes_read = 0
    while bytes_read < num_bytes:
        recv_buf = nailgun_connection.socket.recv(
            num_bytes - bytes_read,
            nailgun_connection.recv_flags)
        if not len(recv_buf):
            raise NailgunException(
                'Server unexpectedly disconnected in recv()',
                NailgunException.CONNECTION_BROKEN)
        buf[bytes_read:bytes_read + len(recv_buf)] = recv_buf
        bytes_read += len(recv_buf)


def process_exit(exit_len, nailgun_connection):
    '''
    Receives an exit code from the nailgun server and sets nailgun_connection.exit_code
    to indicate the client should exit.
    '''
    num_bytes = min(len(nailgun_connection.buf), exit_len)
    recv_to_buffer(num_bytes, nailgun_connection.buf, nailgun_connection)
    nailgun_connection.exit_code = int(''.join(nailgun_connection.buf.raw[:num_bytes]))


def send_heartbeat(nailgun_connection):
    '''
    Sends a heartbeat to the nailgun server to indicate the client is still alive.
    '''
    try:
        send_chunk('', CHUNKTYPE_HEARTBEAT, nailgun_connection)
    except IOError as e:
        # The Nailgun C client ignores SIGPIPE etc. on heartbeats,
        # so we do too. (This typically happens when shutting down.)
        pass


def stdin_thread_main(stdin, queue, shutdown_event, ready_to_send_condition):
    if not stdin:
        return
    try:
        while not shutdown_event.is_set():
            with ready_to_send_condition:
                ready_to_send_condition.wait()
            if shutdown_event.is_set():
                break
            # This is a bit cheesy, but there isn't a great way to
            # portably tell Python to read as much as possible on
            # stdin without blocking.
            buf = stdin.readline()
            if buf == '':
                queue.put((EVENT_STDIN_CLOSED, None))
                break
            queue.put((EVENT_STDIN_CHUNK, buf))
    except Exception as e:
        queue.put((EVENT_STDIN_EXCEPTION, e))


def process_nailgun_stream(nailgun_connection):
    '''
    Processes a single chunk from the nailgun server.
    '''
    recv_to_buffer(
        len(nailgun_connection.header_buf), nailgun_connection.header_buf, nailgun_connection)
    (chunk_len, chunk_type) = struct.unpack_from('>ic', nailgun_connection.header_buf.raw)

    if chunk_type == CHUNKTYPE_STDOUT:
        recv_to_fd(nailgun_connection.stdout, chunk_len, nailgun_connection)
    elif chunk_type == CHUNKTYPE_STDERR:
        recv_to_fd(nailgun_connection.stderr, chunk_len, nailgun_connection)
    elif chunk_type == CHUNKTYPE_EXIT:
        process_exit(chunk_len, nailgun_connection)
    elif chunk_type == CHUNKTYPE_SENDINPUT:
        with nailgun_connection.ready_to_send_condition:
            # Wake up the stdin thread and tell it to read as much data as possible.
            nailgun_connection.ready_to_send_condition.notify()
    else:
        raise NailgunException(
            'Unexpected chunk type: {0}'.format(chunk_type),
            NailgunException.UNEXPECTED_CHUNKTYPE)


def make_nailgun_socket(nailgun_server, nailgun_port=None):
    '''
    Creates and returns a socket connection to the nailgun server.
    '''
    s = None
    if nailgun_server.startswith('local:'):
        try:
            s = socket.socket(socket.AF_UNIX)
        except socket.error as msg:
            raise NailgunException(
                'Could not create local socket connection to server: {0}'.format(msg),
                NailgunException.SOCKET_FAILED)
        socket_addr = nailgun_server[6:]
        try:
            s.connect(socket_addr)
        except socket.error as msg:
            raise NailgunException(
                'Could not connect to local server at {0}: {1}'.format(socket_addr, msg),
                NailgunException.CONNECT_FAILED)
    else:
        socket_addr = nailgun_server
        socket_family = socket.AF_UNSPEC
        for (af, socktype, proto, _, sa) in socket.getaddrinfo(
                nailgun_server, nailgun_port, socket.AF_UNSPEC, socket.SOCK_STREAM):
            try:
                s = socket.socket(af, socktype, proto)
            except socket.error as msg:
                s = None
                continue
            try:
                s.connect(sa)
            except socket.error as msg:
                s.close()
                s = None
                continue
            break
    if s is None:
        raise NailgunException(
            'Could not connect to server {0}:{1}'.format(nailgun_server, nailgun_port),
            NailgunException.NAILGUN_CONNECT_FAILED)
    return s


def main():
    '''
    Main entry point to the nailgun client.
    '''
    default_nailgun_server = os.environ.get('NAILGUN_SERVER', '127.0.0.1')
    default_nailgun_port = int(os.environ.get('NAILGUN_PORT', NAILGUN_PORT_DEFAULT))

    parser = optparse.OptionParser(usage='%prog [options] cmd arg1 arg2 ...')
    parser.add_option('--nailgun-server', default=default_nailgun_server)
    parser.add_option('--nailgun-port', type='int', default=default_nailgun_port)
    parser.add_option('--nailgun-filearg')
    parser.add_option('--nailgun-showversion', action='store_true')
    parser.add_option('--nailgun-help', action='help')
    (options, args) = parser.parse_args()

    if options.nailgun_showversion:
        print 'NailGun client version ' + NAILGUN_VERSION

    if len(args):
        cmd = args.pop(0)
    else:
        cmd = os.path.basename(sys.argv[0])

    # Pass any remaining command line arguments to the server.
    cmd_args = args

    try:
        with NailgunConnection(
                options.nailgun_server,
                server_port=options.nailgun_port) as c:
            exit_code = c.send_command(cmd, cmd_args, options.nailgun_filearg)
            sys.exit(exit_code)
    except NailgunException as e:
        print >>sys.stderr, str(e)
        sys.exit(e.code)
    except KeyboardInterrupt as e:
        pass


if __name__ == '__main__':
    main()
