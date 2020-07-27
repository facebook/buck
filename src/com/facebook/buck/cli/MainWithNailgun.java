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

import com.facebook.buck.cli.BuckDaemon.DaemonCommandExecutionScope;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.support.bgtasks.AsyncBackgroundTaskManager;
import com.facebook.buck.support.bgtasks.BackgroundTaskManager;
import com.facebook.buck.support.cli.config.CliConfig;
import com.facebook.buck.support.state.BuckGlobalState;
import com.facebook.buck.support.state.BuckGlobalStateLifecycleManager;
import com.facebook.buck.util.BgProcessKiller;
import com.facebook.buck.util.CloseableWrapper;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.types.Unit;
import com.facebook.nailgun.NGClientDisconnectReason;
import com.facebook.nailgun.NGContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;

/**
 * The Main entry point for Nailgun calls.
 *
 * <p>This class maintains the state for statically storing daemon fields
 */
public class MainWithNailgun extends AbstractMain {

  private static final Logger LOG = Logger.get(MainWithNailgun.class);

  @Nullable private static FileLock resourcesFileLock = null;

  private static final Platform running_platform = Platform.detect();

  private static final BackgroundTaskManager bgTaskMananger = AsyncBackgroundTaskManager.of();

  private static final BuckGlobalStateLifecycleManager buckGlobalStateLifecycleManager =
      new BuckGlobalStateLifecycleManager();

  private static final Semaphore commandSemaphore = new Semaphore(1);

  private static final AtomicReference<ImmutableList<String>> activeCommandArgs =
      new AtomicReference<>();

  private static volatile Optional<NGContext> commandSemaphoreNgClient = Optional.empty();

  private final NGContext ngContext;

  public MainWithNailgun(NGContext ngContext) {
    super(
        ngContext.out,
        ngContext.err,
        ngContext.in,
        getClientEnvironment(ngContext),
        running_platform,
        DaemonMode.DAEMON);
    this.ngContext = ngContext;
  }

  /**
   * When running as a daemon in the NailGun server, {@link #nailMain(NGContext)} is called instead
   * of {@link MainRunner} so that the given context can be used to listen for client disconnections
   * and interrupt command processing when they occur.
   */
  public static void nailMain(NGContext context) {
    obtainResourceFileLock();
    try (DaemonCommandExecutionScope ignored =
        BuckDaemon.getInstance().getDaemonCommandExecutionScope()) {

      MainWithNailgun mainWithNailgun = new MainWithNailgun(context);
      mainWithNailgun.installUncaughtExceptionHandler();

      MainRunner mainRunner =
          mainWithNailgun.prepareMainRunner(
              bgTaskMananger, buckGlobalStateLifecycleManager, new DaemonCommandManager(context));
      mainRunner.runMainThenExit(context.getArgs(), System.nanoTime());
    }
  }

  /**
   * To prevent 'buck kill' from deleting resources from underneath a 'live' buckd we hold on to the
   * FileLock for the entire lifetime of the process. We depend on the fact that on Linux and MacOS
   * Java FileLock is implemented using the same mechanism as the Python fcntl.lockf method. Should
   * this not be the case we'll simply have a small race between buckd start and `buck kill`.
   */
  private static void obtainResourceFileLock() {
    if (resourcesFileLock != null) {
      return;
    }
    String resourceLockFilePath = System.getProperties().getProperty("buck.resource_lock_path");
    if (resourceLockFilePath == null) {
      // Running from ant, no resource lock needed.
      return;
    }
    try {
      // R+W+A is equivalent to 'a+' in Python (which is how the lock file is opened in Python)
      // because WRITE in Java does not imply truncating the file.
      FileChannel fileChannel =
          FileChannel.open(
              Paths.get(resourceLockFilePath),
              StandardOpenOption.READ,
              StandardOpenOption.WRITE,
              StandardOpenOption.CREATE);
      resourcesFileLock = fileChannel.tryLock(0L, Long.MAX_VALUE, true);
    } catch (IOException | OverlappingFileLockException e) {
      LOG.warn(e, "Error when attempting to acquire resources file lock.");
    }
  }

  private void installUncaughtExceptionHandler() {
    // Override the default uncaught exception handler for background threads to log
    // to java.util.logging then exit the JVM with an error code.
    //
    // (We do this because the default is to just print to stderr and not exit the JVM,
    // which is not safe in a multithreaded environment if the thread held a lock or
    // resource which other threads need.)
    Thread.setDefaultUncaughtExceptionHandler(
        (t, e) -> {

          // Shut down the Nailgun server and make sure it stops trapping System.exit().
          ngContext.getNGServer().shutdown();

          exitWithCode(t, e);
        });
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static ImmutableMap<String, String> getClientEnvironment(NGContext context) {
    return ImmutableMap.copyOf((Map) context.getEnv());
  }

  static class DaemonCommandManager implements CommandManager {

    private final NGContext context;
    @Nullable private BuckGlobalState globalState;

    DaemonCommandManager(NGContext context) {
      this.context = context;
    }

    @Nullable
    @Override
    public CloseableWrapper<Unit> getSemaphoreWrapper(
        BuckCommand command,
        ImmutableList<String> currentArgs,
        ImmutableList.Builder<String> previousArgs) {

      // we can execute read-only commands (query, targets, etc) in parallel
      if (command.isReadOnly()) {
        // using nullable instead of Optional<> to use the object with try-with-resources
        return null;
      }

      while (!commandSemaphore.tryAcquire()) {
        ImmutableList<String> activeCommandArgsCopy = activeCommandArgs.get();
        if (activeCommandArgsCopy != null) {
          // Keep retrying until we either 1) successfully acquire the semaphore or 2) failed to
          // acquire the semaphore and obtain a valid list of args for on going command.
          // In theory, this can stuck in a loop if it never observes such state if other commands
          // winning the race, but I consider this to be a rare corner case.
          previousArgs.addAll(activeCommandArgsCopy);
          return null;
        }

        // Avoid hogging CPU
        Thread.yield();
      }

      commandSemaphoreNgClient = Optional.of(context);

      // Keep track of command that is in progress
      activeCommandArgs.set(currentArgs);

      return CloseableWrapper.of(
          Unit.UNIT,
          unit -> {
            activeCommandArgs.set(null);
            commandSemaphoreNgClient = Optional.empty();
            // TODO(buck_team): have background process killer have its own lifetime management
            BgProcessKiller.disarm();
            commandSemaphore.release();
          });
    }

    @Override
    public void registerGlobalState(BuckGlobalState buckGlobalState) {
      this.globalState = buckGlobalState;

      Thread mainThread = Thread.currentThread();
      context.addClientListener(
          reason -> {
            LOG.info("Nailgun client disconnected with " + reason);
            if (commandSemaphoreNgClient.orElse(null) == context) {
              // Process no longer wants work done on its behalf.
              LOG.debug("Killing background processes on client disconnect");
              BgProcessKiller.interruptBgProcesses();
            }

            if (reason != NGClientDisconnectReason.SESSION_SHUTDOWN) {
              LOG.debug(
                  "Killing all Buck jobs on client disconnect by interrupting the main thread");
              // signal daemon to complete required tasks and interrupt main thread
              // this will hopefully trigger InterruptedException and program shutdown
              buckGlobalState.interruptOnClientExit(mainThread);
            }
          });
    }

    @Override
    public void handleCommandFinished(ExitCode exitCode) throws IOException {
      // signal nailgun that we are not interested in client disconnect events anymore
      context.removeAllClientListeners();

      // Exit Nailgun earlier if command succeeded to now block the client while performing
      // telemetry upload in background
      // For failures, always do it synchronously because exitCode in fact may be overridden up
      // the stack
      // TODO(buck_team): refactor this as in case of exception exitCode is reported incorrectly
      // to the CommandEvent listener
      if (exitCode == ExitCode.SUCCESS
          && globalState != null
          && !globalState
              .getCells()
              .getBuckConfig()
              .getView(CliConfig.class)
              .getFlushEventsBeforeExit()) {
        context.in.close(); // Avoid client exit triggering client disconnection handling.
        context.exit(exitCode.getCode());
      }
    }
  }
}
