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

package com.facebook.buck.intellij.ideabuck.build;

import com.facebook.buck.intellij.ideabuck.api.BuckCellManager;
import com.facebook.buck.intellij.ideabuck.api.BuckCellManager.Cell;
import com.facebook.buck.intellij.ideabuck.config.BuckExecutableSettingsProvider;
import com.facebook.buck.intellij.ideabuck.config.BuckModule;
import com.facebook.buck.intellij.ideabuck.ui.tree.BuckTextNode.TextType;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vcs.LineHandlerHelper;
import com.intellij.openapi.vfs.CharsetToolkit;
import com.intellij.util.EnvironmentUtil;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/** The handler for buck commands with text outputs. */
public abstract class BuckCommandHandler {

  protected static final Logger LOG = Logger.getInstance(BuckCommandHandler.class);
  private static final long LONG_TIME = 10 * 1000;

  protected final Project project;
  protected final BuckModule buckModule;
  protected final BuckCommand command;

  private final GeneralCommandLine commandLine;
  private final Object processStateLock = new Object();
  private static final Pattern CHARACTER_DIGITS_PATTERN = Pattern.compile("(?s).*[A-Z0-9a-z]+.*");
  private final boolean doStartNotify;

  @SuppressWarnings({"FieldAccessedSynchronizedAndUnsynchronized"})
  private Process process;

  @SuppressWarnings({"FieldAccessedSynchronizedAndUnsynchronized"})
  private OSProcessHandler handler;

  /** Character set to use for IO. */
  @SuppressWarnings({"FieldAccessedSynchronizedAndUnsynchronized"})
  private Charset charset = CharsetToolkit.UTF8_CHARSET;

  /** Buck execution start timestamp. */
  private long startTime;

  /** The partial line from stderr stream. */
  private final StringBuilder stderrLine = new StringBuilder();

  private static File calcWorkingDirFor(Project project) {
    BuckCellManager buckCellManager = BuckCellManager.getInstance(project);
    return buckCellManager
        .getDefaultCell()
        .map(Cell::getRootPath)
        .map(Path::toFile)
        .orElse(new File(project.getBasePath()));
  }

  public BuckCommandHandler(Project project, BuckCommand command) {
    this(project, command, /* doStartNotify */ false);
  }

  /**
   * @param project a project
   * @param directory a process directory
   * @param command a command to execute (if empty string, the parameter is ignored)
   * @param doStartNotify true if the handler should call OSHandler#startNotify
   * @deprecated Use {@link BuckCommandHandler(Project, BuckCommand, boolean)}
   */
  @Deprecated
  public BuckCommandHandler(
      Project project, File directory, BuckCommand command, boolean doStartNotify) {
    this(project, command, doStartNotify);
    Path actualPath = commandLine.getWorkDirectory().toPath();
    if (!actualPath.equals(directory.toPath())) {
      LOG.warn(
          "Running buck command \""
              + commandLine.getCommandLineString()
              + "\" from the work directory \""
              + actualPath.toString()
              + "\" and not in the requested directory \""
              + directory.toPath()
              + "\"");
    }
  }

  /**
   * @param project a project
   * @param command a command to execute (if empty string, the parameter is ignored)
   * @param doStartNotify true if the handler should call OSHandler#startNotify
   */
  public BuckCommandHandler(Project project, BuckCommand command, boolean doStartNotify) {
    this.doStartNotify = doStartNotify;

    String buckExecutable =
        BuckExecutableSettingsProvider.getInstance(project).resolveBuckExecutable();

    this.project = project;
    this.buckModule = project.getComponent(BuckModule.class);
    this.command = command;
    commandLine = new GeneralCommandLine();
    commandLine.setExePath(buckExecutable);
    commandLine.withWorkDirectory(calcWorkingDirFor(project));
    commandLine.withEnvironment(EnvironmentUtil.getEnvironmentMap());
    commandLine.addParameter(command.name());
    for (String parameter : command.getParameters()) {
      commandLine.addParameter(parameter);
    }
  }

  /** Start process */
  public synchronized void start() {
    checkNotStarted();

    buckModule
        .getBuckEventsConsumer()
        .sendAsConsoleEvent(commandLine.getCommandLineString(), TextType.INFO);
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

  /** Stop process */
  public synchronized void stop() {
    process.destroy();
  }

  /** @return true if process is started. */
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

  /** @return a context project */
  public Project project() {
    return project;
  }

  /** Start the buck process. */
  @Nullable
  protected Process startProcess() throws ExecutionException {
    synchronized (processStateLock) {
      handler = createProcess(commandLine);
      return handler.getProcess();
    }
  }

  /** Start handling process output streams for the handler. */
  protected void startHandlingStreams() {
    if (handler == null) {
      return;
    }
    handler.addProcessListener(
        new ProcessListener() {
          @Override
          public void startNotified(final ProcessEvent event) {}

          @Override
          public void processTerminated(final ProcessEvent event) {
            BuckCommandHandler.this.processTerminated(event);
          }

          @Override
          public void processWillTerminate(
              final ProcessEvent event, final boolean willBeDestroyed) {}

          @Override
          public void onTextAvailable(final ProcessEvent event, final Key outputType) {
            BuckCommandHandler.this.onTextAvailable(event.getText(), outputType);
          }
        });
    if (doStartNotify) {
      handler.startNotify();
    }
  }

  protected boolean processExitSuccesfull() {
    return process.exitValue() == 0;
  }

  /** Wait for process termination. */
  public void waitFor() {
    checkStarted();
    if (handler != null) {
      // handler.waitFor will wait for a semaphore which will be released when the started
      // process terminates if doStartNotify is true.
      // If the following call never returns, please check the value of doStartNotify
      handler.waitFor();
    }
  }

  public OSProcessHandler createProcess(GeneralCommandLine commandLine) throws ExecutionException {
    // TODO(t7984081): Use ProcessExecutor to start buck process.
    Process process = commandLine.createProcess();
    return new OSProcessHandler(process, commandLine.getCommandLineString(), charset);
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

  public void runInCurrentThreadPostEnd(@Nullable Runnable postEndAction) {
    if (!beforeCommand()) {
      return;
    }

    start();
    if (isStarted()) {
      waitFor();
    }
    afterCommand();
    if (postEndAction != null) {
      postEndAction.run();
    }
    logTime();
  }

  private void logTime() {
    if (startTime > 0) {
      long time = System.currentTimeMillis() - startTime;
      if (!LOG.isDebugEnabled() && time > LONG_TIME) {
        LOG.info(
            String.format(
                "buck %s took %s ms. Command parameters: %n%s",
                command, time, commandLine.getCommandLineString()));
      } else {
        LOG.debug(String.format("buck %s took %s ms", command, time));
      }
    } else {
      LOG.debug(String.format("buck %s finished.", command));
    }
  }

  protected void processTerminated(ProcessEvent event) {
    if (stderrLine.length() != 0) {
      onTextAvailable("\n", ProcessOutputTypes.STDERR);
    }
  }

  protected void onTextAvailable(final String text, final Key outputType) {
    notifyLines(outputType, LineHandlerHelper.splitText(text));
  }

  /**
   * Notify listeners for each complete line. Note that in the case of stderr, the last line is
   * saved.
   */
  protected void notifyLines(final Key outputType, final Iterable<String> lines) {
    if (outputType == ProcessOutputTypes.STDERR) {
      StringBuilder stderr = new StringBuilder();
      for (String line : lines) {
        // Check if the line has at least one character or digit
        if (CHARACTER_DIGITS_PATTERN.matcher(line).matches()) {
          stderr.append(line);
        }
      }
      if (stderr.length() != 0) {
        buckModule.getBuckEventsConsumer().consumeConsoleEvent(stderr.toString());
      }
    }
  }

  protected abstract boolean beforeCommand();

  protected abstract void afterCommand();

  public OSProcessHandler getHandler() {
    return handler;
  }
}
