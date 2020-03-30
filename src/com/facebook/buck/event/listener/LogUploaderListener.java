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

package com.facebook.buck.event.listener;

import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.event.CommandEvent;
import com.facebook.buck.event.chrome_trace.ChromeTraceBuckConfig;
import com.facebook.buck.support.bgtasks.BackgroundTask;
import com.facebook.buck.support.bgtasks.TaskAction;
import com.facebook.buck.support.bgtasks.TaskManagerCommandScope;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.trace.uploader.launcher.UploaderLauncher;
import com.facebook.buck.util.trace.uploader.types.CompressionType;
import com.google.common.eventbus.Subscribe;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;

/** Upload the buck log file to the trace endpoint when the build is finished. */
public class LogUploaderListener implements BuckEventListener {

  private final ChromeTraceBuckConfig config;
  private Optional<ExitCode> commandExitCode = Optional.empty();
  private final Path logFilePath;
  private final Path logDirectoryPath;
  private final BuildId buildId;
  private final TaskManagerCommandScope managerScope;
  private final String traceKindFile;

  public LogUploaderListener(
      ChromeTraceBuckConfig config,
      Path logFilePath,
      Path logDirectoryPath,
      BuildId buildId,
      TaskManagerCommandScope managerScope,
      String traceKindFile) {
    this.config = config;
    this.logFilePath = logFilePath;
    this.logDirectoryPath = logDirectoryPath;
    this.buildId = buildId;
    this.managerScope = managerScope;
    this.traceKindFile = traceKindFile;
  }

  @Subscribe
  public synchronized void commandFinished(CommandEvent.Finished finished) {
    commandExitCode = Optional.of(finished.getExitCode());
  }

  @Subscribe
  public synchronized void commandInterrupted(CommandEvent.Interrupted interrupted) {
    commandExitCode = Optional.of(interrupted.getExitCode());
  }

  @Override
  public synchronized void close() {
    Optional<URI> traceUploadUri = config.getTraceUploadUri();
    if (!traceUploadUri.isPresent()
        || !config.getLogUploadMode().shouldUploadLogs(commandExitCode)) {
      return;
    }

    LogUploaderListenerCloseArgs args =
        ImmutableLogUploaderListenerCloseArgs.of(
            traceUploadUri.get(), logDirectoryPath, logFilePath, buildId);
    BackgroundTask<LogUploaderListenerCloseArgs> task =
        BackgroundTask.of(
            "LogUploaderListener_close", new LogUploaderListenerCloseAction(traceKindFile), args);
    managerScope.schedule(task);
  }

  /**
   * {@link TaskAction} implementation for close() in {@link LogUploaderListener}. Uploads log file
   * in background.
   */
  static class LogUploaderListenerCloseAction implements TaskAction<LogUploaderListenerCloseArgs> {

    private final String traceFileKind;

    public LogUploaderListenerCloseAction(String traceFileKind) {
      this.traceFileKind = traceFileKind;
    }

    @Override
    public void run(LogUploaderListenerCloseArgs args) {
      Path logFile = args.getLogDirectoryPath().resolve("upload_" + traceFileKind + ".log");
      UploaderLauncher.uploadInBackground(
          args.getBuildId(),
          args.getLogFilePath(),
          traceFileKind,
          args.getTraceUploadURI(),
          logFile,
          CompressionType.NONE);
    }
  }

  /** Task arguments passed to {@link LogUploaderListenerCloseAction}. */
  @BuckStyleValue
  abstract static class LogUploaderListenerCloseArgs {
    public abstract URI getTraceUploadURI();

    public abstract Path getLogDirectoryPath();

    public abstract Path getLogFilePath();

    public abstract BuildId getBuildId();
  }
}
