/*
 * Copyright 2012-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.util;

import com.facebook.buck.log.Logger;
import com.google.common.annotations.VisibleForTesting;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;
import com.zaxxer.nuprocess.NuProcess;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import javax.annotation.Nullable;
import javax.lang.model.SourceVersion;

import oshi.SystemInfo;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

/**
 * A helper singleton that provides facilities such as extracting the native process id of a
 * {@link Process} or gathering the process resource consumption.
 */
public class ProcessHelper {

  private static final Logger LOG = Logger.get(ProcessHelper.class);

  // Comparing with the string value to avoid a strong dependency on JDK 9
  private static final boolean IS_JDK9 = SourceVersion.latest().toString().equals("RELEASE_9");

  private static final SystemInfo OSHI = new SystemInfo();

  private static final ProcessHelper INSTANCE = new ProcessHelper();

  /**
   * Gets the singleton instance of this class.
   */
  public static ProcessHelper getInstance() {
    return INSTANCE;
  }

  /**
   * This is a helper singleton.
   */
  @VisibleForTesting
  ProcessHelper() {}

  /**
   * Gets resource consumption of the process for the given pid.
   */
  @Nullable
  public ProcessResourceConsumption getProcessResourceConsumption(long pid) {
    try {
      OperatingSystem os = OSHI.getOperatingSystem();
      OSProcess process = os.getProcess((int) pid);
      return ProcessResourceConsumption.builder()
          .setMemResident(process.getResidentSetSize())
          .setMemSize(process.getVirtualSize())
          .setCpuReal(process.getUpTime())
          .setCpuUser(process.getUserTime())
          .setCpuSys(process.getKernelTime())
          .setCpuTotal(process.getUserTime() + process.getKernelTime())
          .setIoBytesRead(process.getBytesRead())
          .setIoBytesWritten(process.getBytesWritten())
          .setIoTotal(process.getBytesRead() + process.getBytesWritten())
          .build();
    } catch (Exception ex) {
      // do nothing
      return null;
    }
  }

  /**
   * @return whether the process has finished executing or not.
   */
  public boolean hasProcessFinished(Object process) {
    if (process instanceof NuProcess) {
      return !((NuProcess) process).isRunning();
    } else if (process instanceof Process) {
      try {
        ((Process) process).exitValue();
        return true;
      } catch (IllegalThreadStateException e) {
        return false;
      }
    } else {
      throw new IllegalArgumentException("Unknown process class: " + process.getClass().toString());
    }
  }

  /**
   * Gets the native process identifier for the given process instance.
   */
  @Nullable
  public Long getPid(Object process) {
    if (process instanceof NuProcess) {
      return (long) ((NuProcess) process).getPID();
    } else if (process instanceof Process) {
      // Until we switch to JDK9, we will have to go with this per-platform workaround.
      // In JDK9, `Process` has `getPid()` method that does exactly what we need here.
      // http://download.java.net/java/jdk9/docs/api/java/lang/Process.html#getPid--
      Long pid = jdk9ProcessId(process);
      if (pid == null) {
        pid = unixLikeProcessId(process);
      }
      if (pid == null) {
        pid = windowsProcessId(process);
      }
      return pid;
    } else {
      throw new IllegalArgumentException("Unknown process class: " + process.getClass().toString());
    }
  }

  private Long jdk9ProcessId(Object process) {
    if (IS_JDK9) {
      try {
        // Invoking via reflection to avoid a strong dependency on JDK 9
        Method getPid = Process.class.getMethod("getPid");
        return (Long) getPid.invoke(process);
      } catch (Exception e) {
        LOG.warn(e, "Cannot get process id!");
      }
    }
    return null;
  }

  private Long unixLikeProcessId(Object process) {
    Class<?> clazz = process.getClass();
    try {
      if (clazz.getName().equals("java.lang.UNIXProcess")) {
        Field field = clazz.getDeclaredField("pid");
        field.setAccessible(true);
        return (long) field.getInt(process);
      }
    } catch (Exception e) {
      LOG.warn(e, "Cannot get process id!");
    }
    return null;
  }

  private Long windowsProcessId(Object process) {
    Class<?> clazz = process.getClass();
    if (clazz.getName().equals("java.lang.Win32Process") ||
        clazz.getName().equals("java.lang.ProcessImpl")) {
      try {
        Field f = clazz.getDeclaredField("handle");
        f.setAccessible(true);
        long peer = f.getLong(process);
        Pointer pointer = Pointer.createConstant(peer);
        WinNT.HANDLE handle = new WinNT.HANDLE(pointer);
        return (long) Kernel32.INSTANCE.GetProcessId(handle);
      } catch (Exception e) {
        LOG.warn(e, "Cannot get process id!");
      }
    }
    return null;
  }
}
