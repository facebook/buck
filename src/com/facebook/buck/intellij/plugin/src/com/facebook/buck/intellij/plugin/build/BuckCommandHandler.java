/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.intellij.plugin.build;

import com.facebook.buck.intellij.plugin.config.BuckSettingsProvider;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vcs.LineHandlerHelper;
import com.intellij.openapi.vfs.CharsetToolkit;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.charset.Charset;
import java.util.Iterator;

/**
 * The handler for buck commands with text outputs.
 */
public abstract class BuckCommandHandler {

  protected static final Logger LOG = Logger.getInstance(BuckCommandHandler.class);
  private static final long LONG_TIME = 10 * 1000;

  protected final Project project;
  protected final BuckCommand command;

  private final File workingDirectory;
  private final GeneralCommandLine commandLine;
  private final Object processStateLock = new Object();

  @SuppressWarnings({"FieldAccessedSynchronizedAndUnsynchronized"})
  private Process process;

  @SuppressWarnings({"FieldAccessedSynchronizedAndUnsynchronized"})
  private OSProcessHandler handler;

  /**
   * Character set to use for IO.
   */
  @SuppressWarnings({"FieldAccessedSynchronizedAndUnsynchronized"})
  private Charset charset = CharsetToolkit.UTF8_CHARSET;

  /**
   * Buck execution start timestamp.
   */
  private long startTime;

  /**
   * The partial line from stderr stream.
   */
  private final StringBuilder stderrLine = new StringBuilder();

  /**
   * @param project   a project
   * @param directory a process directory
   * @param command   a command to execute (if empty string, the parameter is ignored)
   */
  public BuckCommandHandler(
      Project project,
      File directory,
      BuckCommand command) {

    String buckExecutable = BuckSettingsProvider.getInstance().getState().buckExecutable;

    this.project = project;
    this.command = command;
    commandLine = new GeneralCommandLine();
    commandLine.setExePath(buckExecutable);
    workingDirectory = directory;
    commandLine.withWorkDirectory(workingDirectory);
    commandLine.addParameter(command.name());
  }

  /**
   * Start process
   */
  public synchronized void start() {
    checkNotStarted();

    try {
      startTime = System.currentTimeMillis();
      process = startProcess();
      startHandlingStreams();
    } catch (ProcessCanceledException e) {
      LOG.warn(e);
    } catch (Throwable t) {
      if (!project.isDisposed()) {
        LOG.error(t);
      }
    }
  }

  /**
   * @return true if process is started.
   */
  public final synchronized boolean isStarted() {
    return process != null;
  }

  /**
   * Check that process is not started yet.
   *
   * @throws IllegalStateException if process has been already started
   */
  private void checkNotStarted() {
    if (isStarted()) {
      throw new IllegalStateException("The process has been already started");
    }
  }

  /**
   * Check that process is started.
   *
   * @throws IllegalStateException if process has not been started
   */
  protected final void checkStarted() {
    if (!isStarted()) {
      throw new IllegalStateException("The process is not started yet");
    }
  }

  public GeneralCommandLine command() {
    return commandLine;
  }

  /**
   * @return a context project
   */
  public Project project() {
    return project;
  }

  /**
   * Start the buck process.
   */
  @Nullable
  protected Process startProcess() throws ExecutionException {
    synchronized (processStateLock) {
      final ProcessHandler processHandler = createProcess(commandLine);
      handler = (OSProcessHandler) processHandler;
      return handler.getProcess();
    }
  }

  /**
   * Start handling process output streams for the handler.
   */
  protected void startHandlingStreams() {
    if (handler == null) {
      return;
    }
    handler.addProcessListener(new ProcessListener() {
      public void startNotified(final ProcessEvent event) {
      }

      public void processTerminated(final ProcessEvent event) {
        BuckCommandHandler.this.processTerminated();
      }

      public void processWillTerminate(
          final ProcessEvent event,
          final boolean willBeDestroyed) {
      }

      public void onTextAvailable(final ProcessEvent event, final Key outputType) {
        BuckCommandHandler.this.onTextAvailable(event.getText(), outputType);
      }
    });
    handler.startNotify();
  }

  /**
   * Wait for process termination.
   */
  public void waitFor() {
    checkStarted();
    if (handler != null) {
      handler.waitFor();
    }
  }

  public ProcessHandler createProcess(GeneralCommandLine commandLine)
      throws ExecutionException {
    // TODO(user): Use ProcessExecutor to start buck process.
    Process process = commandLine.createProcess();
    return new MyOSProcessHandler(process, commandLine, getCharset());
  }

  private static class MyOSProcessHandler extends OSProcessHandler {
    private final Charset myCharset;

    public MyOSProcessHandler(
        Process process,
        GeneralCommandLine commandLine,
        Charset charset) {
      super(process, commandLine.getCommandLineString());
      myCharset = charset;
    }

    @Override
    public Charset getCharset() {
      return myCharset;
    }
  }

  /**
   * @return a character set to use for IO.
   */
  public Charset getCharset() {
    return charset;
  }

  public void runInCurrentThread(@Nullable Runnable postStartAction) {
    if (!beforeCommand()) {
      return;
    }

    start();
    if (isStarted()) {
      if (postStartAction != null) {
        postStartAction.run();
      }
      waitFor();
    }
    afterCommand();
    logTime();
  }

  private void logTime() {
    if (startTime > 0) {
      long time = System.currentTimeMillis() - startTime;
      if (!LOG.isDebugEnabled() && time > LONG_TIME) {
        LOG.info(String.format("buck %s took %s ms. Command parameters: %n%s",
            command,
            time,
            commandLine.getCommandLineString()));
      } else {
        LOG.debug(String.format("buck %s took %s ms", command, time));
      }
    } else {
      LOG.debug(String.format("buck %s finished.", command));
    }
  }

  protected void processTerminated() {
    if (stderrLine.length() != 0) {
      onTextAvailable("\n", ProcessOutputTypes.STDERR);
    }
  }

  protected void onTextAvailable(final String text, final Key outputType) {
    Iterator<String> lines = LineHandlerHelper.splitText(text).iterator();
    // Since most of the useful information from buck command are printed to STDERR,
    // currently we only care about STDERR for buck outputs.
    if (ProcessOutputTypes.STDERR == outputType) {
      notifyLines(outputType, lines, stderrLine);
    }
  }

  /**
   * Notify listeners for each complete line. Note that in the case of stderr,
   * the last line is saved.
   *
   * @param outputType  output type
   * @param lines       line iterator
   * @param lineBuilder a line builder
   */
  protected abstract void notifyLines(
      final Key outputType,
      final Iterator<String> lines,
      final StringBuilder lineBuilder);

  protected abstract boolean beforeCommand();

  protected abstract void afterCommand();
}
