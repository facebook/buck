#!/usr/bin/env python

import ctypes
import platform
import sys


def monotonic_time_nanos():
    '''Returns a monotonically-increasing timestamp value in nanoseconds.

    The epoch of the return value is undefined. To use this, you must call
    it more than once and calculate the delta between two calls.
    '''
    # This function should be overwritten below on supported platforms.
    raise Exception('Unsupported platform: ' + platform.system())

NSEC_PER_SEC = 1000000000

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
