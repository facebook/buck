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

import com.facebook.buck.android.exopackage.AndroidDevicesHelper;
import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.rulekey.RuleKeyDiagnosticsMode;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ThrowableConsoleEvent;
import com.facebook.buck.io.filesystem.ProjectFilesystemFactory;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.ClassLoaderCache;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.concurrent.ConcurrencyLimit;
import com.facebook.buck.util.concurrent.ResourceAllocationFairness;
import com.facebook.buck.util.concurrent.ResourceAmountsEstimator;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.worker.WorkerProcessPool;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Closer;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.devtools.build.lib.profiler.Profiler;
import java.io.Closeable;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.immutables.value.Value;

@Value.Immutable(copy = true)
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
  abstract Map<ExecutorPool, ListeningExecutorService> getExecutors();

  @Value.Parameter
  abstract Optional<TargetDevice> getTargetDevice();

  @Value.Parameter
  abstract Optional<AndroidDevicesHelper> getAndroidDevicesHelper();

  /**
   * Worker process pools that are persisted across buck invocations inside buck daemon. If buck is
   * running without daemon, there will be no persisted pools.
   */
  @Value.Parameter
  abstract Optional<ConcurrentMap<String, WorkerProcessPool>> getPersistentWorkerPools();

  @Value.Parameter
  abstract CellPathResolver getCellPathResolver();

  /** See {@link com.facebook.buck.core.build.context.BuildContext#getBuildCellRootPath}. */
  @Value.Parameter
  abstract Path getBuildCellRootPath();

  @Value.Parameter
  abstract ProcessExecutor getProcessExecutor();

  @Value.Parameter
  abstract ProjectFilesystemFactory getProjectFilesystemFactory();

  @Value.Default
  public long getDefaultTestTimeoutMillis() {
    return 0L;
  }

  @Value.Default
  public boolean isCodeCoverageEnabled() {
    return false;
  }

  @Value.Default
  public boolean isInclNoLocationClassesEnabled() {
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
  public RuleKeyDiagnosticsMode getRuleKeyDiagnosticsMode() {
    return RuleKeyDiagnosticsMode.NEVER;
  }

  /**
   * Worker process pools that you can populate as needed. These will be destroyed as soon as buck
   * invocation finishes, thus, these pools are not persisted across buck invocations.
   */
  @Value.Default
  public ConcurrentMap<String, WorkerProcessPool> getWorkerProcessPools() {
    return new ConcurrentHashMap<>();
  }

  @Value.Default
  public ConcurrencyLimit getConcurrencyLimit() {
    return new ConcurrencyLimit(
        /* threadLimit */ Runtime.getRuntime().availableProcessors(),
        ResourceAllocationFairness.FAIR,
        ResourceAmountsEstimator.DEFAULT_MANAGED_THREAD_COUNT,
        ResourceAmountsEstimator.DEFAULT_AMOUNTS,
        ResourceAmountsEstimator.DEFAULT_MAXIMUM_AMOUNTS);
  }

  @Value.Default
  public ClassLoaderCache getClassLoaderCache() {
    return new ClassLoaderCache();
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

  @Value.Default
  public Optional<Profiler> getProfiler() {
    return Optional.empty();
  }

  public void logError(Throwable error, String msg, Object... formatArgs) {
    getBuckEventBus().post(ThrowableConsoleEvent.create(error, msg, formatArgs));
  }

  public void postEvent(BuckEvent event) {
    getBuckEventBus().post(event);
  }

  public ExecutionContext createSubContext(
      PrintStream newStdout, PrintStream newStderr, Optional<Verbosity> verbosityOverride) {
    Console console =
        new Console(
            verbosityOverride.orElse(this.getConsole().getVerbosity()),
            newStdout,
            newStderr,
            this.getConsole().getAnsi());

    // This should replace (or otherwise retain) all of the closeable parts of the context.
    return ExecutionContext.builder()
        .from(this)
        .setConsole(console)
        .setProcessExecutor(getProcessExecutor().cloneWithOutputStreams(newStdout, newStderr))
        .setClassLoaderCache(getClassLoaderCache().addRef())
        .setAndroidDevicesHelper(Optional.empty())
        .setWorkerProcessPools(new ConcurrentHashMap<String, WorkerProcessPool>())
        .build();
  }

  @Override
  public void close() throws IOException {
    // Using a Closer makes it easy to ensure that exceptions from one of the closeables don't
    // cancel the others.
    try (Closer closer = Closer.create()) {
      closer.register(getClassLoaderCache()::close);
      getAndroidDevicesHelper().ifPresent(closer::register);
      // The closer closes in reverse order, so do the clear first.
      closer.register(getWorkerProcessPools()::clear);
      for (WorkerProcessPool pool : getWorkerProcessPools().values()) {
        closer.register(pool);
      }
      getProfiler().ifPresent(profiler -> closer.register(profiler::stop));
    }
  }
}
