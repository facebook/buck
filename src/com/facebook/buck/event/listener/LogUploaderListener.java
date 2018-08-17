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

package com.facebook.buck.event.listener;

import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.event.CommandEvent;
import com.facebook.buck.event.chrome_trace.ChromeTraceBuckConfig;
import com.facebook.buck.support.bgtasks.BackgroundTask;
import com.facebook.buck.support.bgtasks.BackgroundTaskManager;
import com.facebook.buck.support.bgtasks.ImmutableBackgroundTask;
import com.facebook.buck.support.bgtasks.TaskAction;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.trace.uploader.launcher.UploaderLauncher;
import com.facebook.buck.util.trace.uploader.types.CompressionType;
import com.google.common.eventbus.Subscribe;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import org.immutables.value.Value;

/** Upload the buck log file to the trace endpoint when the build is finished. */
public class LogUploaderListener implements BuckEventListener {

  private final ChromeTraceBuckConfig config;
  private Optional<ExitCode> commandExitCode = Optional.empty();
  private final Path logFilePath;
  private final Path logDirectoryPath;
  private final BuildId buildId;
  private final BackgroundTaskManager bgTaskManager;

  public LogUploaderListener(
      ChromeTraceBuckConfig config,
      Path logFilePath,
      Path logDirectoryPath,
      BuildId buildId,
      BackgroundTaskManager bgTaskManager) {
    this.config = config;
    this.logFilePath = logFilePath;
    this.logDirectoryPath = logDirectoryPath;
    this.buildId = buildId;
    this.bgTaskManager = bgTaskManager;
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
        LogUploaderListenerCloseArgs.of(
            traceUploadUri.get(), logDirectoryPath, logFilePath, buildId);
    BackgroundTask<LogUploaderListenerCloseArgs> task =
        ImmutableBackgroundTask.<LogUploaderListenerCloseArgs>builder()
            .setAction(new LogUploaderListenerCloseAction())
            .setActionArgs(args)
            .setName("LogUploaderListener_close")
            .build();
    bgTaskManager.schedule(task);
  }

  /**
   * {@link TaskAction} implementation for close() in {@link LogUploaderListener}. Uploads log file
   * in background.
   */
  static class LogUploaderListenerCloseAction implements TaskAction<LogUploaderListenerCloseArgs> {
    @Override
    public void run(LogUploaderListenerCloseArgs args) {
      Path logFile = args.getLogDirectoryPath().resolve("upload-build-log.log");
      UploaderLauncher.uploadInBackground(
          args.getBuildId(),
          args.getLogFilePath(),
          "build_log",
          args.getTraceUploadURI(),
          logFile,
          CompressionType.NONE);
    }
  }

  /** Task arguments passed to {@link LogUploaderListenerCloseAction}. */
  @Value.Immutable
  @BuckStyleImmutable
  abstract static class AbstractLogUploaderListenerCloseArgs {
    @Value.Parameter
    public abstract URI getTraceUploadURI();

    @Value.Parameter
    public abstract Path getLogDirectoryPath();

    @Value.Parameter
    public abstract Path getLogFilePath();

    @Value.Parameter
    public abstract BuildId getBuildId();
  }
}
