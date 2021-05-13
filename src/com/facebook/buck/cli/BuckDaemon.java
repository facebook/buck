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

package com.facebook.buck.cli;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.util.BgProcessKiller;
import com.facebook.buck.util.Libc;
import com.facebook.buck.util.environment.Platform;
import com.facebook.nailgun.NGListeningAddress;
import com.facebook.nailgun.NGServer;
import com.sun.jna.LastErrorException;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/** The buckd process, which is the long running nailgun server. */
public final class BuckDaemon {
  /**
   * Force JNA to be initialized early to avoid deadlock race condition.
   *
   * <p>
   *
   * <p>See: https://github.com/java-native-access/jna/issues/652
   */
  public static final int JNA_POINTER_SIZE = Native.POINTER_SIZE;

  private static final int AFTER_COMMAND_AUTO_GC_DELAY_MS = 5000;
  private static final int SUBSEQUENT_GC_DELAY_MS = 10000;

  private static final Duration DAEMON_SLAYER_TIMEOUT = Duration.ofDays(1);

  private static @Nullable BuckDaemon instance;

  private static final Logger LOG = Logger.get(BuckDaemon.class);

  private final NGServer server;
  private final IdleKiller idleKiller;
  private final @Nullable SocketLossKiller unixDomainSocketLossKiller;
  private final AtomicInteger activeTasks = new AtomicInteger(0);

  /** Single thread for running short-lived tasks outside the command context. */
  private final ScheduledExecutorService housekeepingExecutorService =
      Executors.newSingleThreadScheduledExecutor();

  private static final boolean isCMS =
      ManagementFactory.getGarbageCollectorMXBeans().stream()
          .filter(GarbageCollectorMXBean::isValid)
          .map(GarbageCollectorMXBean::getName)
          .anyMatch(Predicate.isEqual("ConcurrentMarkSweep"));

  /** The command ran when starting a new long living buckd session. */
  public static void main(String[] args) {
    try {
      SystemInfoLogger.logFileLimits();
      SystemInfoLogger.logParentProcessChain();

      if (daemonizeIfPossible()) {
        BgProcessKiller.init();
        LOG.info("initialized bg session killer");
      }
    } catch (Throwable ex) {
      System.err.println(String.format("buckd: fatal error %s", ex));
      System.exit(1);
    }

    if (args.length != 2) {
      System.err.println("Usage: buckd socketpath heartbeatTimeout");
      return;
    }

    String socketPath = args[0];
    int heartbeatTimeout = Integer.parseInt(args[1]);
    // Strip out optional local: prefix.  This server only use domain sockets.
    if (socketPath.startsWith("local:")) {
      socketPath = socketPath.substring("local:".length());
    }
    SecurityManager securityManager = System.getSecurityManager();
    NGServer server =
        new NGServer(
            new NGListeningAddress(socketPath),
            1, // store only 1 NGSession in a pool to avoid excessive memory usage
            heartbeatTimeout);
    instance = new BuckDaemon(server, Paths.get(socketPath));
    try {
      server.run();
    } catch (RuntimeException e) {
      // server.run() might throw (for example, if this process loses the race with another
      // process to become the daemon for a given Buck root). Letting the exception go would
      // kill this thread, but other non-daemon threads have already been started and we haven't
      // yet installed the unhandled exception handler that would call System.exit, so this
      // process would live forever as a zombie. We catch the exception, re-instate the original
      // security manager (because NailGun has replaced it with one that blocks System.exit, and
      // doesn't restore the original if an exception occurs), and exit.
      System.setSecurityManager(securityManager);
      LOG.error(e, "Exception thrown in NailGun server.");
    }
    System.exit(0);
  }

  static BuckDaemon getInstance() {
    return Objects.requireNonNull(instance, "BuckDaemon should be initialized.");
  }

  private BuckDaemon(NGServer server, Path socketPath) {
    this.server = server;
    this.idleKiller =
        new IdleKiller(housekeepingExecutorService, DAEMON_SLAYER_TIMEOUT, this::killServer);
    this.unixDomainSocketLossKiller =
        Platform.detect() == Platform.WINDOWS
            ? null
            : new SocketLossKiller(
                housekeepingExecutorService, socketPath.toAbsolutePath(), this::killServer);
  }

  DaemonCommandExecutionScope getDaemonCommandExecutionScope() {
    return new DaemonCommandExecutionScope();
  }

  private void performCleanup(int nTimes) {
    int tasks = activeTasks.decrementAndGet();
    if (tasks > 0) {
      return;
    }
    // Potentially there is a race condition - new command comes exactly at this point and got
    // under GC right away. Unlucky. We ignore that.
    System.gc();

    // schedule next collection to release more memory to operating system if garbage collector
    // releases it in steps
    if (nTimes > 1) {
      activeTasks.incrementAndGet();
      housekeepingExecutorService.schedule(
          () -> performCleanup(nTimes - 1), SUBSEQUENT_GC_DELAY_MS, TimeUnit.MILLISECONDS);
      return;
    }
    // we are now idle with no more GC, so we schedule the idle killer if no commands are currently
    // running.
    activeTasks.getAndUpdate(
        taskCount -> {
          /**
           * the function here may be retried multiple times as supposed to be side effect free, so
           * we code it such that it acts as if its an atomic operation.
           *
           * <p>Therefore, in the 0 case, we always reset the task, and in the non 0 case, we always
           * clear the task.
           */
          if (taskCount == 0) {
            idleKiller.setIdleKillTask();
            return 0;
          }
          idleKiller.clearIdleKillTask();
          return taskCount;
        });
  }

  private void killServer() {
    server.shutdown();
  }

  class DaemonCommandExecutionScope implements AutoCloseable {

    DaemonCommandExecutionScope() {
      if (unixDomainSocketLossKiller != null) {
        unixDomainSocketLossKiller.arm(); // Arm the socket loss killer also.
      }

      activeTasks.incrementAndGet();
      idleKiller.clearIdleKillTask();
    }

    @Override
    public void close() {
      // Concurrent Mark and Sweep (CMS) garbage collector releases memory to operating system
      // in multiple steps, even given that full collection is performed at each step. So if CMS
      // collector is used we call System.gc() up to 4 times with some interval, and call it
      // just once for any other major collector.
      // With Java 9 we could just use -XX:-ShrinkHeapInSteps flag.

      int nTimes = isCMS ? 4 : 1;

      housekeepingExecutorService.schedule(
          () -> performCleanup(nTimes), AFTER_COMMAND_AUTO_GC_DELAY_MS, TimeUnit.MILLISECONDS);
    }
  }

  private static void markFdCloseOnExec(int fd) {
    int fdFlags;
    fdFlags = Libc.INSTANCE.fcntl(fd, Libc.Constants.rFGETFD);
    if (fdFlags == -1) {
      throw new LastErrorException(Native.getLastError());
    }
    fdFlags |= Libc.Constants.rFDCLOEXEC;
    if (Libc.INSTANCE.fcntl(fd, Libc.Constants.rFSETFD, fdFlags) == -1) {
      throw new LastErrorException(Native.getLastError());
    }
  }

  private static boolean daemonizeIfPossible() {
    String osName = System.getProperty("os.name");
    Libc.OpenPtyLibrary openPtyLibrary;
    Platform platform = Platform.detect();
    if (platform == Platform.LINUX) {
      Libc.Constants.rTIOCSCTTY = Libc.Constants.LINUX_TIOCSCTTY;
      Libc.Constants.rFDCLOEXEC = Libc.Constants.LINUX_FD_CLOEXEC;
      Libc.Constants.rFGETFD = Libc.Constants.LINUX_F_GETFD;
      Libc.Constants.rFSETFD = Libc.Constants.LINUX_F_SETFD;
      openPtyLibrary = Native.loadLibrary("libutil", Libc.OpenPtyLibrary.class);
    } else if (platform == Platform.MACOS) {
      Libc.Constants.rTIOCSCTTY = Libc.Constants.DARWIN_TIOCSCTTY;
      Libc.Constants.rFDCLOEXEC = Libc.Constants.DARWIN_FD_CLOEXEC;
      Libc.Constants.rFGETFD = Libc.Constants.DARWIN_F_GETFD;
      Libc.Constants.rFSETFD = Libc.Constants.DARWIN_F_SETFD;
      openPtyLibrary =
          Native.loadLibrary(com.sun.jna.Platform.C_LIBRARY_NAME, Libc.OpenPtyLibrary.class);
    } else {
      LOG.info("not enabling process killing on nailgun exit: unknown OS %s", osName);
      return false;
    }

    // Making ourselves a session leader with setsid disconnects us from our controlling terminal
    int ret = Libc.INSTANCE.setsid();
    if (ret < 0) {
      LOG.warn("cannot enable background process killing: %s", Native.getLastError());
      return false;
    }

    LOG.info("enabling background process killing for buckd");

    IntByReference master = new IntByReference();
    IntByReference slave = new IntByReference();

    if (openPtyLibrary.openpty(master, slave, Pointer.NULL, Pointer.NULL, Pointer.NULL) != 0) {
      throw new RuntimeException("Failed to open pty");
    }

    // Deliberately leak the file descriptors for the lifetime of this process; NuProcess can
    // sometimes leak file descriptors to children, so make sure these FDs are marked close-on-exec.
    markFdCloseOnExec(master.getValue());
    markFdCloseOnExec(slave.getValue());

    // Make the pty our controlling terminal; works because we disconnected above with setsid.
    if (Libc.INSTANCE.ioctl(slave.getValue(), Pointer.createConstant(Libc.Constants.rTIOCSCTTY), 0)
        == -1) {
      throw new RuntimeException("Failed to set pty");
    }

    LOG.info("enabled background process killing for buckd");
    return true;
  }
}
