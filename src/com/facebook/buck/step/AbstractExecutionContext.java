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

package com.facebook.buck.step;

import com.facebook.buck.android.AndroidPlatformTarget;
import com.facebook.buck.android.NoAndroidSdkException;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ThrowableConsoleEvent;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.shell.WorkerProcessPool;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.ClassLoaderCache;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.concurrent.ConcurrencyLimit;
import com.facebook.buck.util.concurrent.ResourceAllocationFairness;
import com.facebook.buck.util.concurrent.ResourceAmountsEstimator;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListeningExecutorService;

import org.immutables.value.Value;

import java.io.Closeable;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractExecutionContext implements Closeable {

  @Value.Parameter
  abstract Console getConsole();

  @Value.Parameter
  abstract BuckEventBus getBuckEventBus();

  @Value.Parameter
  abstract Platform getPlatform();

  @Value.Parameter
  abstract ImmutableMap<String, String> getEnvironment();

  @Value.Parameter
  abstract JavaPackageFinder getJavaPackageFinder();

  @Value.Parameter
  abstract ObjectMapper getObjectMapper();

  @Value.Parameter
  abstract Map<ExecutorPool, ListeningExecutorService> getExecutors();

  @Value.Parameter
  abstract Optional<TargetDevice> getTargetDevice();

  @Value.Parameter
  abstract Optional<TargetDeviceOptions> getTargetDeviceOptions();

  @Value.Parameter
  abstract Optional<AdbOptions> getAdbOptions();

  /**
   * Returns an {@link AndroidPlatformTarget} if the user specified one. If the user failed to
   * specify one, an exception will be thrown.
   */
  @Value.Default
  public Supplier<AndroidPlatformTarget> getAndroidPlatformTargetSupplier() {
    return AndroidPlatformTarget.EXPLODING_ANDROID_PLATFORM_TARGET_SUPPLIER;
  }

  @Value.Default
  public long getDefaultTestTimeoutMillis() {
    return 0L;
  }

  @Value.Default
  public boolean isCodeCoverageEnabled() {
    return false;
  }

  @Value.Default
  public boolean shouldReportAbsolutePaths() {
    return false;
  }

  @Value.Default
  public boolean isDebugEnabled() {
    return false;
  }

  @Value.Default
  public ConcurrentMap<String, WorkerProcessPool> getWorkerProcessPools() {
    return new ConcurrentHashMap<>();
  }

  @Value.Default
  public ConcurrencyLimit getConcurrencyLimit() {
    return new ConcurrencyLimit(
        /* threadLimit */ Runtime.getRuntime().availableProcessors(),
        /* loadLimit */ Double.POSITIVE_INFINITY,
        ResourceAllocationFairness.FAIR,
        ResourceAmountsEstimator.DEFAULT_MANAGED_THREAD_COUNT,
        ResourceAmountsEstimator.DEFAULT_AMOUNTS,
        ResourceAmountsEstimator.DEFAULT_MAXIMUM_AMOUNTS);
  }

  @Value.Default
  public ClassLoaderCache getClassLoaderCache() {
    return new ClassLoaderCache();
  }

  @Value.Default
  public ProcessExecutor getProcessExecutor() {
    return new DefaultProcessExecutor(getConsole());
  }

  @Value.Derived
  public Verbosity getVerbosity() {
    return getConsole().getVerbosity();
  }

  @Value.Derived
  public PrintStream getStdErr() {
    return getConsole().getStdErr();
  }

  @Value.Derived
  public PrintStream getStdOut() {
    return getConsole().getStdErr();
  }

  @Value.Derived
  public BuildId getBuildId() {
    return getBuckEventBus().getBuildId();
  }

  @Value.Derived
  public Ansi getAnsi() {
    return getConsole().getAnsi();
  }

  /**
   * Returns the {@link AndroidPlatformTarget}, if present. If not, throws a
   * {@link NoAndroidSdkException}. Use this when your logic requires the user to specify the
   * location of an Android SDK. A user who is building a "pure Java" (i.e., not Android) project
   * using Buck should never have to exercise this code path.
   * <p>
   * If the location of an Android SDK is optional, then use
   * {@link #getAndroidPlatformTargetSupplier()}.
   * @throws NoAndroidSdkException if no AndroidPlatformTarget is available
   */
  @Value.Lazy
  public AndroidPlatformTarget getAndroidPlatformTarget() throws NoAndroidSdkException {
    return getAndroidPlatformTargetSupplier().get();
  }

  public ListeningExecutorService getExecutorService(ExecutorPool p) {
    ListeningExecutorService executorService = getExecutors().get(p);
    Preconditions.checkNotNull(executorService);
    return executorService;
  }

  public String getPathToAdbExecutable() throws NoAndroidSdkException {
    return getAndroidPlatformTarget().getAdbExecutable().toString();
  }

  public void logError(Throwable error, String msg, Object... formatArgs) {
    getBuckEventBus().post(ThrowableConsoleEvent.create(error, msg, formatArgs));
  }

  public void postEvent(BuckEvent event) {
    getBuckEventBus().post(event);
  }

  public ExecutionContext createSubContext(
      PrintStream newStdout,
      PrintStream newStderr,
      Optional<Verbosity> verbosityOverride) {
    Console console = new Console(
        verbosityOverride.or(this.getConsole().getVerbosity()),
        newStdout,
        newStderr,
        this.getConsole().getAnsi());

    return ExecutionContext.builder()
        .from(this)
        .setConsole(console)
        .setProcessExecutor(new DefaultProcessExecutor(console))
        .setClassLoaderCache(getClassLoaderCache().addRef())
        .setWorkerProcessPools(new ConcurrentHashMap<String, WorkerProcessPool>())
        .build();
  }

  @Override
  public void close() throws IOException {
    getClassLoaderCache().close();
    try {
      for (WorkerProcessPool pool : getWorkerProcessPools().values()) {
        pool.close();
      }
    } finally {
      getWorkerProcessPools().clear();
    }
  }
}
