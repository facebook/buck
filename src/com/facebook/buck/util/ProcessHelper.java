/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.util;

import com.facebook.buck.core.util.log.Logger;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Suppliers;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;
import com.zaxxer.nuprocess.NuProcess;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import javax.lang.model.SourceVersion;
import oshi.SystemInfo;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

/**
 * A helper singleton that provides facilities such as extracting the native process id of a {@link
 * Process} or gathering the process resource consumption.
 */
public class ProcessHelper {

  private static final Logger LOG = Logger.get(ProcessHelper.class);

  // Comparing with the string value to avoid a strong dependency on JDK 9+
  // TODO: Remove the version checks once Buck has been migrated to Java 10/11.
  private static final boolean IS_JDK9_OR_LATER =
      SourceVersion.latest().toString().equals("RELEASE_9")
          || SourceVersion.latest().toString().equals("RELEASE_10")
          || SourceVersion.latest().toString().equals("RELEASE_11");

  private static final SystemInfo OSHI = new SystemInfo();

  private static final ProcessHelper INSTANCE = new ProcessHelper();

  /** Gets the singleton instance of this class. */
  public static ProcessHelper getInstance() {
    return INSTANCE;
  }

  private Supplier<ProcessTree> processTree =
      Suppliers.memoizeWithExpiration(
          () -> {
            ProcessTree tree = new ProcessTree();
            try {
              LOG.verbose("Getting process tree...");
              OperatingSystem os = OSHI.getOperatingSystem();
              List<OSProcess> processes = os.getProcesses(100, OperatingSystem.ProcessSort.NEWEST);
              for (OSProcess process : processes) {
                tree.add(process);
              }
              LOG.verbose("Process tree built.");
            } catch (Exception e) {
              LOG.warn(e, "Cannot get the process tree!");
            }
            return tree;
          },
          1,
          TimeUnit.SECONDS);

  /** This is a helper singleton. */
  @VisibleForTesting
  ProcessHelper() {}

  /** Gets resource consumption of the process subtree rooted at the process with the given pid. */
  @Nullable
  public ProcessResourceConsumption getTotalResourceConsumption(long pid) {
    ProcessResourceConsumption[] res = new ProcessResourceConsumption[] {null};
    ProcessTree tree = processTree.get();
    tree.visitAllDescendants(
        pid,
        (childPid, childNode) -> {
          ProcessResourceConsumption childRes =
              getProcessResourceConsumptionInternal(childNode.info);
          res[0] = ProcessResourceConsumption.getTotal(res[0], childRes);
        });
    // fallback to the root process only if the above fails
    return (res[0] != null) ? res[0] : getProcessResourceConsumption(pid);
  }

  /** Gets resource consumption of the process with the given pid. */
  @Nullable
  public ProcessResourceConsumption getProcessResourceConsumption(long pid) {
    try {
      OperatingSystem os = OSHI.getOperatingSystem();
      OSProcess process = os.getProcess((int) pid);
      return getProcessResourceConsumptionInternal(process);
    } catch (Exception ex) {
      // do nothing
      return null;
    }
  }

  @Nullable
  static ProcessResourceConsumption getProcessResourceConsumptionInternal(
      @Nullable OSProcess process) {
    if (process == null) {
      return null;
    }
    return ProcessResourceConsumption.of(
        process.getResidentSetSize(),
        process.getVirtualSize(),
        process.getUpTime(),
        process.getUserTime(),
        process.getKernelTime(),
        process.getUserTime() + process.getKernelTime(),
        process.getBytesRead(),
        process.getBytesWritten(),
        process.getBytesRead() + process.getBytesWritten());
  }

  /** @return whether the process has finished executing or not. */
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
      throw new IllegalArgumentException("Unknown process class: " + process.getClass());
    }
  }

  /** Gets the native process identifier for the current process. */
  @Nullable
  public Long getPid() {
    try {
      return (long) OSHI.getOperatingSystem().getProcessId();
    } catch (Exception e) {
      LOG.warn(e, "Cannot get the current process id!");
      return null;
    }
  }

  /** Gets the native process identifier for the given process instance. */
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
      throw new IllegalArgumentException("Unknown process class: " + process.getClass());
    }
  }

  @Nullable
  private Long jdk9ProcessId(Object process) {
    if (IS_JDK9_OR_LATER) {
      try {
        // Invoking via reflection to avoid a strong dependency on JDK 9
        Method getPid = Process.class.getMethod("pid");
        return (Long) getPid.invoke(process);
      } catch (Exception e) {
        LOG.warn(e, "Cannot get process id!");
      }
    }
    return null;
  }

  @Nullable
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

  @Nullable
  private Long windowsProcessId(Object process) {
    Class<?> clazz = process.getClass();
    if (clazz.getName().equals("java.lang.Win32Process")
        || clazz.getName().equals("java.lang.ProcessImpl")) {
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

  /** A simple representation of a process tree. */
  private static class ProcessTree {
    public interface Visitor {
      void visit(long pid, Node node);
    }

    public static class Node {
      public final long pid;
      // May be null if it's the parent of a process, and we sampled a subset of processes which
      // didn't include the parent.
      @Nullable private OSProcess info;
      private final List<Node> children = new ArrayList<>();

      public Node(long pid) {
        this.pid = pid;
      }

      private void addChild(Node child) {
        children.add(child);
      }
    }

    private final Map<Long, Node> nodes = new HashMap<>();

    private Node getOrCreate(long pid) {
      Node node = nodes.get(pid);
      if (node == null) {
        node = new Node(pid);
        nodes.put(pid, node);
      }
      return node;
    }

    public Node add(OSProcess info) {
      Node node = getOrCreate(info.getProcessID());
      if (node.info == null) {
        node.info = info;
      }
      Node parent = getOrCreate(info.getParentProcessID());
      parent.addChild(node);
      return node;
    }

    @Nullable
    public Node get(long pid) {
      return nodes.get(pid);
    }

    public void visitAllDescendants(long pid, Visitor visitor) {
      Node root = get(pid);
      if (root != null) {
        visitor.visit(pid, root);
        for (Node child : root.children) {
          visitAllDescendants(child.pid, visitor);
        }
      }
    }
  }
}
