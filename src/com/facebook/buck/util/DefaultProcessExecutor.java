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

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Predicates.not;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.IsolatedEventBus;
import com.facebook.buck.util.concurrent.MostExecutors;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.types.Unit;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Executes a {@link Process} and blocks until it is finished. */
public class DefaultProcessExecutor implements ProcessExecutor {

  private static final Logger LOG = Logger.get(ProcessExecutor.class);

  private static final ThreadPoolExecutor PROCESS_EXECUTOR_THREAD_POOL =
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
  private final Verbosity verbosity;
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
        console.getVerbosity(),
        ProcessHelper.getInstance(),
        ProcessRegistry.getInstance());
  }

  @VisibleForTesting
  protected DefaultProcessExecutor(
      PrintStream stdOutStream,
      PrintStream stdErrStream,
      Ansi ansi,
      Verbosity verbosity,
      ProcessHelper processHelper,
      ProcessRegistry processRegistry) {
    this.stdOutStream = stdOutStream;
    this.stdErrStream = stdErrStream;
    this.ansi = ansi;
    this.verbosity = verbosity;
    this.processHelper = processHelper;
    this.processRegistry = processRegistry;
  }

  @Override
  public ProcessExecutor cloneWithOutputStreams(
      PrintStream newStdOutStream, PrintStream newStdErrStream) {
    return new DefaultProcessExecutor(
        newStdOutStream, newStdErrStream, ansi, verbosity, processHelper, processRegistry);
  }

  @Override
  public ProcessExecutor withDownwardAPI(
      DownwardApiProcessExecutorFactory factory,
      IsolatedEventBus buckEventBus,
      String actionId,
      Clock clock) {
    return factory.create(
        this, ConsoleParams.of(ansi.isAnsiTerminal(), verbosity), buckEventBus, actionId, clock);
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
          command.stream()
              .map(Escaper.CREATE_PROCESS_ESCAPER::apply)
              .collect(ImmutableList.toImmutableList());
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
    return new LaunchedProcessImpl(process, pb.command());
  }

  @Override
  public Result waitForLaunchedProcess(LaunchedProcess launchedProcess)
      throws InterruptedException {
    LaunchedProcessImpl launchedProcessImpl = getLaunchedProcessImpl(launchedProcess);
    checkState(!waitForInternal(launchedProcessImpl.process, Optional.empty(), Optional.empty()));
    int exitCode = launchedProcessImpl.process.exitValue();
    return new Result(
        exitCode, false, Optional.empty(), Optional.empty(), launchedProcess.getCommand());
  }

  @Override
  public Result waitForLaunchedProcessWithTimeout(
      LaunchedProcess launchedProcess, long millis, Optional<Consumer<Process>> timeOutHandler)
      throws InterruptedException {
    Process process = getLaunchedProcessImpl(launchedProcess).process;
    boolean timedOut = waitForInternal(process, Optional.of(millis), timeOutHandler);
    int exitCode = !timedOut ? process.exitValue() : 1;
    return new Result(
        exitCode, timedOut, Optional.empty(), Optional.empty(), launchedProcess.getCommand());
  }

  /**
   * Waits up to {@code timeoutMillis} milliseconds for the given process to finish. If no timeout
   * is present, waits forever.
   *
   * @return whether the wait has timed out.
   */
  private boolean waitForInternal(
      Process process, Optional<Long> timeoutMs, Optional<Consumer<Process>> timeOutHandler)
      throws InterruptedException {

    if (timeoutMs.isPresent()) {
      if (!process.waitFor(timeoutMs.get(), TimeUnit.MILLISECONDS)) {
        try {
          timeOutHandler.ifPresent(consumer -> consumer.accept(process));
        } catch (RuntimeException e1) {
          LOG.error(e1, "ProcessExecutor timeOutHandler threw an exception, ignored.");
        }
        return true;
      }
    } else {
      process.waitFor();
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
  @Override
  public Result execute(
      LaunchedProcess launchedProcess,
      Set<Option> options,
      Optional<Stdin> stdin,
      Optional<Long> timeOutMs,
      Optional<Consumer<Process>> timeOutHandler)
      throws InterruptedException {
    try {
      return getExecutionResult(launchedProcess, options, stdin, timeOutMs, timeOutHandler);
    } finally {
      launchedProcess.close();
    }
  }

  private Result getExecutionResult(
      LaunchedProcess launchedProcess,
      Set<Option> options,
      Optional<Stdin> stdin,
      Optional<Long> timeOutMs,
      Optional<Consumer<Process>> timeOutHandler)
      throws InterruptedException {
    Process process = getLaunchedProcessImpl(launchedProcess).process;
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
    Future<Unit> stdOutTerminationFuture = PROCESS_EXECUTOR_THREAD_POOL.submit(stdOut);
    Future<Unit> stdErrTerminationFuture = PROCESS_EXECUTOR_THREAD_POOL.submit(stdErr);

    boolean timedOut;

    // Block until the Process completes.
    try {
      // If a stdin string was specific, then write that first.  This shouldn't cause
      // deadlocks, as the stdout/stderr consumers are running in separate threads.
      if (stdin.isPresent()) {
        stdin.get().writeTo(process.getOutputStream());
        process.getOutputStream().close();
      }

      // Wait for the process to complete.  If a timeout was given, we wait up to the timeout
      // for it to finish then force kill it.  If no timeout was given, just wait for it forever.
      timedOut = waitForInternal(process, timeOutMs, timeOutHandler);
      if (timedOut && !processHelper.hasProcessFinished(process)) {
        process.destroyForcibly();
      }

      stdOutTerminationFuture.get();
      stdErrTerminationFuture.get();
    } catch (ExecutionException | IOException e) {
      // Buck was killed while waiting for the consumers to finish or while writing stdin
      // to the process. This means either the user killed the process or a step failed
      // causing us to kill all other running steps. Neither of these is an exceptional
      // situation.
      LOG.warn(e, "Process threw exception when being executed.");
      return new Result(1, launchedProcess.getCommand());
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
      if (!shouldPrintStdOut) {
        stdoutText
            .filter(not(String::isEmpty))
            .ifPresent(
                text -> {
                  LOG.verbose("Writing captured stdout text to stream: [%s]", text);
                  stdOutStream.print(text);
                });
      }

      if (!shouldPrintStdErr) {
        stderrText
            .filter(not(String::isEmpty))
            .ifPresent(
                text -> {
                  LOG.verbose("Writing captured stderr text to stream: [%s]", text);
                  stdErrStream.print(text);
                });
      }
    }

    return new Result(exitCode, timedOut, stdoutText, stderrText, launchedProcess.getCommand());
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

  /**
   * Wraps a {@link Process} and exposes only its I/O streams, so callers have to pass it back to
   * this class.
   */
  @VisibleForTesting
  public class LaunchedProcessImpl implements LaunchedProcess {

    public final Process process;
    public final ImmutableList<String> command;

    public LaunchedProcessImpl(Process process, List<String> command) {
      this.process = process;
      this.command = ImmutableList.copyOf(command);
    }

    @Override
    public boolean isAlive() {
      return process.isAlive();
    }

    @Override
    public ImmutableList<String> getCommand() {
      return command;
    }

    @Override
    public OutputStream getStdin() {
      return process.getOutputStream();
    }

    @Override
    public InputStream getStdout() {
      return process.getInputStream();
    }

    @Override
    public InputStream getStderr() {
      return process.getErrorStream();
    }

    @Override
    public void close() {
      process.destroy();
    }
  }

  private LaunchedProcessImpl getLaunchedProcessImpl(LaunchedProcess launchedProcess) {
    LaunchedProcess process = launchedProcess;
    if (launchedProcess instanceof DelegateLaunchedProcess) {
      DelegateLaunchedProcess delegateLaunchedProcess = (DelegateLaunchedProcess) launchedProcess;
      process = delegateLaunchedProcess.getDelegate();
    }

    checkState(process instanceof LaunchedProcessImpl);
    return (LaunchedProcessImpl) process;
  }
}
