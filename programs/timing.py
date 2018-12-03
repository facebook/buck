#!/usr/bin/env python
# Copyright 2018-present Facebook, Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may
# not use this file except in compliance with the License. You may obtain
# a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations
# under the License.


import ctypes
import platform
import sys


def monotonic_time_nanos():
    """Returns a monotonically-increasing timestamp value in nanoseconds.

    The epoch of the return value is undefined. To use this, you must call
    it more than once and calculate the delta between two calls.
    """
    # This function should be overwritten below on supported platforms.
    raise Exception("Unsupported platform: " + platform.system())


NSEC_PER_SEC = 1000000000


def set_posix_time_nanos(clock_gettime, clock_id):
    global monotonic_time_nanos

    class struct_timespec(ctypes.Structure):
        _fields_ = [("tv_sec", ctypes.c_long), ("tv_nsec", ctypes.c_long)]

    clock_gettime.argtypes = [ctypes.c_int, ctypes.POINTER(struct_timespec)]

    def _monotonic_time_nanos_posix():
        t = struct_timespec()
        clock_gettime(clock_id, ctypes.byref(t))
        return int(t.tv_sec * NSEC_PER_SEC + t.tv_nsec)

    monotonic_time_nanos = _monotonic_time_nanos_posix


if platform.system() == "Linux":
    # From <linux/time.h>, available since 2.6.28 (released 24-Dec-2008).
    CLOCK_MONOTONIC_RAW = 4
    librt = ctypes.CDLL("librt.so.1", use_errno=True)
    clock_gettime = librt.clock_gettime
    set_posix_time_nanos(clock_gettime, CLOCK_MONOTONIC_RAW)

elif platform.system() == "Darwin":
    # From <mach/mach_time.h>
    KERN_SUCCESS = 0
    libSystem = ctypes.CDLL("/usr/lib/libSystem.dylib", use_errno=True)
    mach_timebase_info = libSystem.mach_timebase_info

    class struct_mach_timebase_info(ctypes.Structure):
        _fields_ = [("numer", ctypes.c_uint32), ("denom", ctypes.c_uint32)]

    mach_timebase_info.argtypes = [ctypes.POINTER(struct_mach_timebase_info)]
    mach_ti = struct_mach_timebase_info()
    ret = mach_timebase_info(ctypes.byref(mach_ti))
    if ret != KERN_SUCCESS:
        raise Exception("Could not get mach_timebase_info, error: " + str(ret))
    mach_absolute_time = libSystem.mach_absolute_time
    mach_absolute_time.restype = ctypes.c_uint64

    def _monotonic_time_nanos_darwin():
        return int((mach_absolute_time() * mach_ti.numer) / mach_ti.denom)

    monotonic_time_nanos = _monotonic_time_nanos_darwin
elif platform.system() == "Windows":
    # From <Winbase.h>
    perf_frequency = ctypes.c_uint64()
    ctypes.windll.kernel32.QueryPerformanceFrequency(ctypes.byref(perf_frequency))

    def _monotonic_time_nanos_windows():
        perf_counter = ctypes.c_uint64()
        ctypes.windll.kernel32.QueryPerformanceCounter(ctypes.byref(perf_counter))
        return int(perf_counter.value * NSEC_PER_SEC / perf_frequency.value)

    monotonic_time_nanos = _monotonic_time_nanos_windows
elif sys.platform == "cygwin":
    k32 = ctypes.CDLL("Kernel32", use_errno=True)
    perf_frequency = ctypes.c_uint64()
    k32.QueryPerformanceFrequency(ctypes.byref(perf_frequency))

    def _monotonic_time_nanos_cygwin():
        perf_counter = ctypes.c_uint64()
        k32.QueryPerformanceCounter(ctypes.byref(perf_counter))
        return int(perf_counter.value * NSEC_PER_SEC / perf_frequency.value)

    monotonic_time_nanos = _monotonic_time_nanos_cygwin
elif platform.system() == "FreeBSD":
    CLOCK_MONOTONIC = 4
    # On FreeBSD9 and FreeBSD10
    libc = ctypes.CDLL("libc.so.7", use_errno=True)
    clock_gettime = libc.clock_gettime
    set_posix_time_nanos(clock_gettime, CLOCK_MONOTONIC)
