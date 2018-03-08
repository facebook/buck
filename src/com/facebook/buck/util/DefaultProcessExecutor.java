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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.facebook.buck.log.Logger;
import com.facebook.buck.util.concurrent.MostExecutors;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/** Executes a {@link Process} and blocks until it is finished. */
public class DefaultProcessExecutor implements ProcessExecutor {

  private static final Logger LOG = Logger.get(ProcessExecutor.class);
  private static final ThreadPoolExecutor THREAD_POOL =
      new ThreadPoolExecutor(
          0,
          Integer.MAX_VALUE,
          1,
          TimeUnit.SECONDS,
          new SynchronousQueue<>(),
          new MostExecutors.NamedThreadFactory("ProcessExecutor"));

  private final PrintStream stdOutStream;
  private final PrintStream stdErrStream;
  private final Ansi ansi;
  private final ProcessHelper processHelper;
  private final ProcessRegistry processRegistry;

  /**
   * Creates a new {@link DefaultProcessExecutor} with the specified parameters used for writing the
   * output of the process.
   */
  public DefaultProcessExecutor(Console console) {
    this(
        console.getStdOut(),
        console.getStdErr(),
        console.getAnsi(),
        ProcessHelper.getInstance(),
        ProcessRegistry.getInstance());
  }

  protected DefaultProcessExecutor(
      PrintStream stdOutStream,
      PrintStream stdErrStream,
      Ansi ansi,
      ProcessHelper processHelper,
      ProcessRegistry processRegistry) {
    this.stdOutStream = stdOutStream;
    this.stdErrStream = stdErrStream;
    this.ansi = ansi;
    this.processHelper = processHelper;
    this.processRegistry = processRegistry;
  }

  @Override
  public ProcessExecutor cloneWithOutputStreams(
      PrintStream newStdOutStream, PrintStream newStdErrStream) {
    return new DefaultProcessExecutor(
        newStdOutStream, newStdErrStream, ansi, processHelper, processRegistry);
  }

  @Override
  public Result launchAndExecute(ProcessExecutorParams params)
      throws InterruptedException, IOException {
    return launchAndExecute(params, ImmutableMap.of());
  }

  @Override
  public Result launchAndExecute(ProcessExecutorParams params, ImmutableMap<String, String> context)
      throws InterruptedException, IOException {
    return launchAndExecute(
        params,
        context,
        ImmutableSet.of(),
        /* stdin */ Optional.empty(),
        /* timeOutMs */ Optional.empty(),
        /* timeOutHandler */ Optional.empty());
  }

  @Override
  public Result launchAndExecute(
      ProcessExecutorParams params,
      Set<Option> options,
      Optional<String> stdin,
      Optional<Long> timeOutMs,
      Optional<Consumer<Process>> timeOutHandler)
      throws InterruptedException, IOException {
    return launchAndExecute(params, ImmutableMap.of(), options, stdin, timeOutMs, timeOutHandler);
  }

  @Override
  public Result launchAndExecute(
      ProcessExecutorParams params,
      ImmutableMap<String, String> context,
      Set<Option> options,
      Optional<String> stdin,
      Optional<Long> timeOutMs,
      Optional<Consumer<Process>> timeOutHandler)
      throws InterruptedException, IOException {
    return execute(launchProcess(params, context), options, stdin, timeOutMs, timeOutHandler);
  }

  @Override
  public LaunchedProcess launchProcess(ProcessExecutorParams params) throws IOException {
    return launchProcess(params, ImmutableMap.of());
  }

  @Override
  public LaunchedProcess launchProcess(
      ProcessExecutorParams params, ImmutableMap<String, String> context) throws IOException {
    ImmutableList<String> command = params.getCommand();
    /* On Windows, we need to escape the arguments we hand off to `CreateProcess`.  See
     * http://blogs.msdn.com/b/twistylittlepassagesallalike/archive/2011/04/23/everyone-quotes-arguments-the-wrong-way.aspx
     * for more details.
     */
    if (Platform.detect() == Platform.WINDOWS) {
      command =
          ImmutableList.copyOf(Iterables.transform(command, Escaper.CREATE_PROCESS_ESCAPER::apply));
    }
    ProcessBuilder pb = new ProcessBuilder(command);
    if (params.getDirectory().isPresent()) {
      pb.directory(params.getDirectory().get().toFile());
    }
    if (params.getEnvironment().isPresent()) {
      pb.environment().clear();
      pb.environment().putAll(params.getEnvironment().get());
    }
    if (params.getRedirectInput().isPresent()) {
      pb.redirectInput(params.getRedirectInput().get());
    }
    if (params.getRedirectOutput().isPresent()) {
      pb.redirectOutput(params.getRedirectOutput().get());
    }
    if (params.getRedirectError().isPresent()) {
      pb.redirectError(params.getRedirectError().get());
    }
    if (params.getRedirectErrorStream().isPresent()) {
      pb.redirectErrorStream(params.getRedirectErrorStream().get());
    }
    Process process = BgProcessKiller.startProcess(pb);
    processRegistry.registerProcess(process, params, context);
    return new LaunchedProcessImpl(process);
  }

  @Override
  public void destroyLaunchedProcess(LaunchedProcess launchedProcess) {
    Preconditions.checkState(launchedProcess instanceof LaunchedProcessImpl);
    ((LaunchedProcessImpl) launchedProcess).process.destroy();
  }

  @Override
  public Result waitForLaunchedProcess(LaunchedProcess launchedProcess)
      throws InterruptedException {
    Preconditions.checkState(launchedProcess instanceof LaunchedProcessImpl);
    int exitCode = ((LaunchedProcessImpl) launchedProcess).process.waitFor();
    return new Result(exitCode, false, Optional.empty(), Optional.empty());
  }

  @Override
  public Result waitForLaunchedProcessWithTimeout(
      LaunchedProcess launchedProcess, long millis, Optional<Consumer<Process>> timeOutHandler)
      throws InterruptedException {
    Preconditions.checkState(launchedProcess instanceof LaunchedProcessImpl);
    Process process = ((LaunchedProcessImpl) launchedProcess).process;
    boolean timedOut = waitForTimeoutInternal(process, millis, timeOutHandler);
    int exitCode = !timedOut ? process.exitValue() : 1;
    return new Result(exitCode, timedOut, Optional.empty(), Optional.empty());
  }

  /**
   * Waits up to {@code millis} milliseconds for the given process to finish.
   *
   * @return whether the wait has timed out.
   */
  private boolean waitForTimeoutInternal(
      Process process, long millis, Optional<Consumer<Process>> timeOutHandler)
      throws InterruptedException {
    Future<?> waiter =
        THREAD_POOL.submit(
            () -> {
              try {
                process.waitFor();
              } catch (InterruptedException e) {
                // The thread waiting has hit its timeout.
              }
            });
    try {
      waiter.get(millis, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      try {
        timeOutHandler.ifPresent(consumer -> consumer.accept(process));
      } catch (RuntimeException e1) {
        LOG.error(e1, "ProcessExecutor timeOutHandler threw an exception, ignored.");
      }
      waiter.cancel(true);
      return true;
    } catch (ExecutionException e) {
      throw new IllegalStateException("Unexpected exception thrown from waiter.", e);
    }
    return false;
  }

  /**
   * Executes the specified already-launched process.
   *
   * <p>If {@code options} contains {@link Option#PRINT_STD_OUT}, then the stdout of the process
   * will be written directly to the stdout passed to the constructor of this executor. Otherwise,
   * the stdout of the process will be made available via {@link Result#getStdout()}.
   *
   * <p>If {@code options} contains {@link Option#PRINT_STD_ERR}, then the stderr of the process
   * will be written directly to the stderr passed to the constructor of this executor. Otherwise,
   * the stderr of the process will be made available via {@link Result#getStderr()}.
   *
   * @param timeOutHandler If present, this method will be called before the process is killed.
   */
  public Result execute(
      LaunchedProcess launchedProcess,
      Set<Option> options,
      Optional<String> stdin,
      Optional<Long> timeOutMs,
      Optional<Consumer<Process>> timeOutHandler)
      throws InterruptedException {
    Preconditions.checkState(launchedProcess instanceof LaunchedProcessImpl);
    Process process = ((LaunchedProcessImpl) launchedProcess).process;
    // Read stdout/stderr asynchronously while running a Process.
    // See http://stackoverflow.com/questions/882772/capturing-stdout-when-calling-runtime-exec
    boolean shouldPrintStdOut = options.contains(Option.PRINT_STD_OUT);
    boolean expectingStdOut = options.contains(Option.EXPECTING_STD_OUT);
    PrintStream stdOutToWriteTo = shouldPrintStdOut ? stdOutStream : new CapturingPrintStream();
    InputStreamConsumer stdOut =
        new InputStreamConsumer(
            process.getInputStream(),
            InputStreamConsumer.createAnsiHighlightingHandler(
                /* flagOutputWrittenToStream */ !shouldPrintStdOut && !expectingStdOut,
                stdOutToWriteTo,
                ansi));

    boolean shouldPrintStdErr = options.contains(Option.PRINT_STD_ERR);
    boolean expectingStdErr = options.contains(Option.EXPECTING_STD_ERR);
    PrintStream stdErrToWriteTo = shouldPrintStdErr ? stdErrStream : new CapturingPrintStream();
    InputStreamConsumer stdErr =
        new InputStreamConsumer(
            process.getErrorStream(),
            InputStreamConsumer.createAnsiHighlightingHandler(
                /* flagOutputWrittenToStream */ !shouldPrintStdErr && !expectingStdErr,
                stdErrToWriteTo,
                ansi));

    // Consume the streams so they do not deadlock.
    Future<Void> stdOutTerminationFuture = THREAD_POOL.submit(stdOut);
    Future<Void> stdErrTerminationFuture = THREAD_POOL.submit(stdErr);

    boolean timedOut = false;

    // Block until the Process completes.
    try {
      // If a stdin string was specific, then write that first.  This shouldn't cause
      // deadlocks, as the stdout/stderr consumers are running in separate threads.
      if (stdin.isPresent()) {
        try (OutputStreamWriter stdinWriter = new OutputStreamWriter(process.getOutputStream())) {
          stdinWriter.write(stdin.get());
        }
      }

      // Wait for the process to complete.  If a timeout was given, we wait up to the timeout
      // for it to finish then force kill it.  If no timeout was given, just wait for it using
      // the regular `waitFor` method.
      if (timeOutMs.isPresent()) {
        timedOut = waitForTimeoutInternal(process, timeOutMs.get(), timeOutHandler);
        if (!processHelper.hasProcessFinished(process)) {
          process.destroyForcibly();
        }
      } else {
        process.waitFor();
      }

      stdOutTerminationFuture.get();
      stdErrTerminationFuture.get();
    } catch (ExecutionException | IOException e) {
      // Buck was killed while waiting for the consumers to finish or while writing stdin
      // to the process. This means either the user killed the process or a step failed
      // causing us to kill all other running steps. Neither of these is an exceptional
      // situation.
      LOG.warn(e, "Process threw exception when being executed.");
      return new Result(1);
    } finally {
      process.destroy();
      process.waitFor();
    }

    Optional<String> stdoutText = getDataIfNotPrinted(stdOutToWriteTo, shouldPrintStdOut);
    Optional<String> stderrText = getDataIfNotPrinted(stdErrToWriteTo, shouldPrintStdErr);

    // Report the exit code of the Process.
    int exitCode = process.exitValue();

    // If the command has failed and we're not being explicitly quiet, ensure everything gets
    // printed.
    if (exitCode != 0 && !options.contains(Option.IS_SILENT)) {
      if (!shouldPrintStdOut && !stdoutText.get().isEmpty()) {
        LOG.verbose("Writing captured stdout text to stream: [%s]", stdoutText.get());
        stdOutStream.print(stdoutText.get());
      }
      if (!shouldPrintStdErr && !stderrText.get().isEmpty()) {
        LOG.verbose("Writing captured stderr text to stream: [%s]", stderrText.get());
        stdErrStream.print(stderrText.get());
      }
    }

    return new Result(exitCode, timedOut, stdoutText, stderrText);
  }

  private static Optional<String> getDataIfNotPrinted(
      PrintStream printStream, boolean shouldPrint) {
    if (!shouldPrint) {
      CapturingPrintStream capturingPrintStream = (CapturingPrintStream) printStream;
      return Optional.of(capturingPrintStream.getContentsAsString(UTF_8));
    } else {
      return Optional.empty();
    }
  }
}
