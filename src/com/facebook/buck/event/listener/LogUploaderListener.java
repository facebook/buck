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
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.event.CommandEvent;
import com.facebook.buck.event.chrome_trace.ChromeTraceBuckConfig;
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

  public LogUploaderListener(
      ChromeTraceBuckConfig config, Path logFilePath, Path logDirectoryPath) {
    this.config = config;
    this.logFilePath = logFilePath;
    this.logDirectoryPath = logDirectoryPath;
  }

  @Override
  public synchronized void outputTrace(BuildId buildId) {
    uploadLogIfConfigured(buildId);
  }

  @Subscribe
  public synchronized void commandFinished(CommandEvent.Finished finished) {
    commandExitCode = Optional.of(finished.getExitCode());
  }

  @Subscribe
  public synchronized void commandInterrupted(CommandEvent.Interrupted interrupted) {
    commandExitCode = Optional.of(interrupted.getExitCode());
  }

  private void uploadLogIfConfigured(BuildId buildId) {
    Optional<URI> traceUploadUri = config.getTraceUploadUri();
    if (!traceUploadUri.isPresent()
        || !config.getLogUploadMode().shouldUploadLogs(commandExitCode)) {
      return;
    }

    Path logFile = logDirectoryPath.resolve("upload-build-log.log");
    UploaderLauncher.uploadInBackground(
        buildId, logFilePath, "build_log", traceUploadUri.get(), logFile, CompressionType.NONE);
  }
}
