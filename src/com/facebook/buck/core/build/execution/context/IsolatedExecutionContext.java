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

package com.facebook.buck.core.build.execution.context;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.facebook.buck.downwardapi.processexecutor.DefaultNamedPipeEventHandler;
import com.facebook.buck.downwardapi.processexecutor.DownwardApiProcessExecutor;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.IsolatedEventBus;
import com.facebook.buck.event.StepEvent;
import com.facebook.buck.event.ThrowableConsoleEvent;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.ClassLoaderCache;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.NamedPipeEventHandler;
import com.facebook.buck.util.NamedPipeEventHandlerFactory;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.worker.WorkerProcessPool;
import com.facebook.buck.workertool.WorkerToolExecutor;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Closer;
import java.io.Closeable;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.immutables.value.Value;

/** The context exposed for executing {@code IsolatedStep}s */
@BuckStyleValueWithBuilder
public abstract class IsolatedExecutionContext implements Closeable {

  /** Returns an {@link IsolatedExecutionContext}. */
  public static IsolatedExecutionContext of(
      ClassLoaderCache classLoaderCache,
      IsolatedEventBus eventBus,
      Console console,
      Platform platform,
      ProcessExecutor processExecutor,
      AbsPath ruleCellRoot,
      String actionId,
      Clock clock) {
    return ImmutableIsolatedExecutionContext.builder()
        .setIsolatedEventBus(eventBus)
        .setConsole(console)
        .setPlatform(platform)
        .setProcessExecutor(processExecutor)
        .setRuleCellRoot(ruleCellRoot)
        .setActionId(actionId)
        .setClock(clock)
        .setWorkerToolPools(new ConcurrentHashMap<>())
        .setPersistentWorkerToolExecutorPools(Optional.empty())
        .setClassLoaderCache(classLoaderCache.addRef())
        .build();
  }

  /** Returns an {@link IsolatedExecutionContext}. */
  public static IsolatedExecutionContext of(
      IsolatedEventBus eventBus,
      Console console,
      Platform platform,
      ProcessExecutor processExecutor,
      AbsPath ruleCellRoot,
      String actionId,
      Clock clock) {
    return ImmutableIsolatedExecutionContext.builder()
        .setIsolatedEventBus(eventBus)
        .setConsole(console)
        .setPlatform(platform)
        .setProcessExecutor(processExecutor)
        .setRuleCellRoot(ruleCellRoot)
        .setActionId(actionId)
        .setClock(clock)
        .setWorkerToolPools(new ConcurrentHashMap<>())
        .setPersistentWorkerToolExecutorPools(Optional.empty())
        .build();
  }

  public abstract Console getConsole();

  public abstract IsolatedEventBus getIsolatedEventBus();

  public abstract Platform getPlatform();

  public abstract ImmutableMap<String, String> getEnvironment();

  public abstract ProcessExecutor getProcessExecutor();

  /**
   * The path to the root cell associated with the current rules being interacted with by this
   * context.
   *
   * <p>For example, consider two cells: cell1 and cell2. If a build like "buck build cell2//:bar"
   * was invoked from cell1, this method would return cell2's path in the context for bar. Note that
   * if anything from cell1 is executed during "buck build cell2//:bar", this method would return
   * cell1's path for those things.
   *
   * <p>The return value of this method does not have anything to do with what command line args
   * were passed. Consider cell1 and cell2 again. When executing a rule in cell1 (like cell1//:foo),
   * this would return cell1's path. When executing a rule in cell2 (like cell2//:bar) this would
   * return cell2's path.
   */
  public abstract AbsPath getRuleCellRoot();

  /**
   * Returns an id of executing action. Typically represents that fully qualified name of the build
   * target.
   */
  public abstract String getActionId();

  /** Returns clock associated with the current invocation. */
  public abstract Clock getClock();

  public abstract ConcurrentMap<String, WorkerProcessPool<WorkerToolExecutor>> getWorkerToolPools();

  public abstract Optional<ConcurrentMap<String, WorkerProcessPool<WorkerToolExecutor>>>
      getPersistentWorkerToolExecutorPools();

  /**
   * Returns {@link DownwardApiProcessExecutor} created from the given {@link
   * #getProcessExecutor()}.
   */
  public DownwardApiProcessExecutor getDownwardApiProcessExecutor() {
    return getDownwardApiProcessExecutor(getProcessExecutor());
  }

  /** Returns {@link DownwardApiProcessExecutor} created from the given {@code processExecutor}. */
  public DownwardApiProcessExecutor getDownwardApiProcessExecutor(ProcessExecutor processExecutor) {
    return getDownwardApiProcessExecutor(processExecutor, DefaultNamedPipeEventHandler.FACTORY);
  }

  /**
   * Returns {@link DownwardApiProcessExecutor} that would use {@link NamedPipeEventHandler} create
   * by passed {@link NamedPipeEventHandlerFactory}
   */
  public DownwardApiProcessExecutor getDownwardApiProcessExecutor(
      NamedPipeEventHandlerFactory namedPipeEventReaderFactory) {
    return getDownwardApiProcessExecutor(getProcessExecutor(), namedPipeEventReaderFactory);
  }

  /**
   * Returns {@link DownwardApiProcessExecutor} that would use {@link NamedPipeEventHandler} create
   * by passed {@link NamedPipeEventHandlerFactory}
   */
  private DownwardApiProcessExecutor getDownwardApiProcessExecutor(
      ProcessExecutor processExecutor, NamedPipeEventHandlerFactory namedPipeEventReaderFactory) {
    return (DownwardApiProcessExecutor)
        processExecutor.withDownwardAPI(
            DownwardApiProcessExecutor.FACTORY,
            namedPipeEventReaderFactory,
            getIsolatedEventBus(),
            getActionId(),
            getClock());
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
    return getIsolatedEventBus().getBuildId();
  }

  @Value.Derived
  public Ansi getAnsi() {
    return getConsole().getAnsi();
  }

  public void logError(Throwable error, String msg, Object... formatArgs) {
    getIsolatedEventBus().post(ThrowableConsoleEvent.create(error, msg, formatArgs));
  }

  public void postEvent(ConsoleEvent event) {
    getIsolatedEventBus().post(event);
  }

  public void postEvent(StepEvent event) {
    getIsolatedEventBus().post(event);
  }

  @Override
  public void close() throws IOException {
    // Using a Closer makes it easy to ensure that exceptions from one of the closeables don't
    // cancel the others.
    try (Closer closer = Closer.create()) {
      registerCloseables(closer);
    }
  }

  protected void registerCloseables(Closer closer) {
    closer.register(getClassLoaderCache()::close);
  }

  /** Creates SubContext */
  public IsolatedExecutionContext createSubContext(
      PrintStream newStdout, PrintStream newStderr, Optional<Verbosity> verbosityOverride) {
    Console console = getConsole();
    Console newConsole =
        new Console(
            verbosityOverride.orElse(console.getVerbosity()),
            newStdout,
            newStderr,
            console.getAnsi());

    // This should replace (or otherwise retain) all of the closeable parts of the context.
    return ImmutableIsolatedExecutionContext.builder()
        .setIsolatedEventBus(getIsolatedEventBus())
        .setConsole(newConsole)
        .setPlatform(getPlatform())
        .setProcessExecutor(getProcessExecutor().cloneWithOutputStreams(newStdout, newStderr))
        .setRuleCellRoot(getRuleCellRoot())
        .setClassLoaderCache(getClassLoaderCache().addRef())
        .setEnvironment(getEnvironment())
        .setActionId(getActionId())
        .setClock(getClock())
        .setWorkerToolPools(getWorkerToolPools())
        .build();
  }
}
