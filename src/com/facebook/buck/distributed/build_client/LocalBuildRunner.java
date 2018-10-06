/*
 * Copyright 2018-present Facebook, Inc.
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
package com.facebook.buck.distributed.build_client;

import com.facebook.buck.command.Build;
import com.facebook.buck.command.LocalBuildExecutorInvoker;
import com.facebook.buck.core.build.distributed.synchronization.RemoteBuildRuleCompletionWaiter;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.util.CleanBuildShutdownException;
import com.facebook.buck.util.ExitCode;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/** Asynchronously runs a Stampede local build. Provides methods to kill/wait/get status codes. */
public class LocalBuildRunner {
  private static final Logger LOG = Logger.get(LocalBuildRunner.class);

  private final ExecutorService executor;
  private boolean isDownloadHeavyBuild;
  private final RemoteBuildRuleCompletionWaiter remoteBuildRuleCompletionWaiter;
  private final AtomicReference<Build> buildReference;
  private final CountDownLatch initializeBuildLatch;
  private final CountDownLatch buildPhaseLatch;
  private final LocalBuildExecutorInvoker localBuildExecutorInvoker;
  private final String localBuildType;
  private volatile Optional<ExitCode> localBuildExitCode = Optional.empty();
  private final Optional<CountDownLatch> waitForLocalBuildCalledLatch;

  @GuardedBy("this")
  @Nullable
  private Future<?> runLocalBuildFuture = null;

  public LocalBuildRunner(
      ExecutorService executor,
      LocalBuildExecutorInvoker localBuildExecutorInvoker,
      String localBuildType,
      boolean isDownloadHeavyBuild,
      RemoteBuildRuleCompletionWaiter remoteBuildRuleCompletionWaiter,
      Optional<CountDownLatch> waitForLocalBuildCalledLatch) {
    this.executor = executor;
    this.localBuildExecutorInvoker = localBuildExecutorInvoker;
    this.localBuildType = localBuildType;
    this.isDownloadHeavyBuild = isDownloadHeavyBuild;
    this.remoteBuildRuleCompletionWaiter = remoteBuildRuleCompletionWaiter;
    this.waitForLocalBuildCalledLatch = waitForLocalBuildCalledLatch;

    this.buildReference = new AtomicReference<>(null);
    this.initializeBuildLatch = new CountDownLatch(1);
    this.buildPhaseLatch = new CountDownLatch(1);
  }

  /** Starts local build in a separate thread */
  public synchronized void runLocalBuildAsync() {
    runLocalBuildFuture = executor.submit(this::safeExecuteLocalBuild);
  }

  /** @return CountDownLatch that signals end build (either local or distributed) */
  public CountDownLatch getBuildPhaseLatch() {
    return buildPhaseLatch;
  }

  private void safeExecuteLocalBuild() {
    ExitCode exitCode = ExitCode.FATAL_GENERIC;
    try {
      LOG.info(String.format("Invoking LocalBuildExecutorInvoker for %s build..", localBuildType));
      exitCode =
          localBuildExecutorInvoker.executeLocalBuild(
              isDownloadHeavyBuild,
              remoteBuildRuleCompletionWaiter,
              initializeBuildLatch,
              buildReference);

    } catch (IOException e) {
      LOG.error(e, String.format("Stampede local %s build failed with exception.", localBuildType));
      throw new RuntimeException(e);
    } catch (InterruptedException e) {
      LOG.error(
          e, String.format("Stampede local %s build thread was interrupted.", localBuildType));
      Thread.currentThread().interrupt();
      return;
    } finally {
      localBuildExitCode = Optional.of(exitCode);
      String finishedMessage =
          String.format(
              "Stampede local %s build has finished with exit code [%d]",
              localBuildType, exitCode.getCode());
      LOG.info(finishedMessage);
      buildPhaseLatch.countDown();
    }
  }

  /** @return exit code of local build */
  public Optional<ExitCode> getExitCode() {
    return localBuildExitCode;
  }

  /** @return True if local build has finished */
  public boolean isFinished() {
    return localBuildExitCode.isPresent();
  }

  /**
   * Tells build engine to kill the local build, and then waits for local build main thread to
   * terminate
   *
   * @param message Message to send to build engine when terminating build
   * @throws InterruptedException
   */
  public void cancelAndWait(String message) throws InterruptedException {
    synchronized (this) {
      Objects.requireNonNull(
          runLocalBuildFuture,
          String.format(
              "Attempting to kill Stampede local %s build which has not been started yet.",
              localBuildType));
    }

    LOG.info(
        String.format(
            "Attempting to kill Stampede local %s build. Waiting for Build to be initialized..",
            localBuildType));
    initializeBuildLatch.await();
    Build build = Objects.requireNonNull(buildReference.get());
    LOG.info(String.format("Killing Build for Stampede local %s build..", localBuildType));
    build.terminateBuildWithFailure(new CleanBuildShutdownException(message));
    LOG.info(
        String.format(
            "Sent termination signal to Build for Stampede local %s build.", localBuildType));

    waitUntilFinished();

    LOG.info(String.format("Stampede local %s build cancellation complete.", localBuildType));
  }

  /**
   * Waits for the local build to finish
   *
   * @throws InterruptedException
   */
  public void waitUntilFinished() throws InterruptedException {
    waitForLocalBuildCalledLatch.ifPresent(latch -> latch.countDown());
    try {
      synchronized (this) {
        Objects.requireNonNull(runLocalBuildFuture).get();
      }
    } catch (ExecutionException e) {
      LOG.error(
          e,
          String.format(
              "Exception thrown whilst waiting for Stampede local %s build to finish.",
              localBuildType));
    }
  }

  /** @return True if local build finished successfully */
  public boolean finishedSuccessfully() {
    Optional<ExitCode> exitCode = getExitCode();
    return exitCode.isPresent() && exitCode.get() == ExitCode.SUCCESS;
  }
}
