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
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.chrome_trace.ChromeTraceBuckConfig;
import com.facebook.buck.event.chrome_trace.ChromeTraceWriter;
import com.facebook.buck.io.file.PathListing;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.support.bgtasks.TaskAction;
import com.facebook.buck.util.Threads;
import com.facebook.buck.util.trace.uploader.launcher.UploaderLauncher;
import com.facebook.buck.util.trace.uploader.types.CompressionType;
import com.facebook.buck.util.trace.uploader.types.TraceKind;
import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/** TaskAction implementation for the close() logic in {@link ChromeTraceBuildListener}. */
class ChromeTraceBuildListenerCloseAction
    implements TaskAction<ChromeTraceBuildListenerCloseAction.ChromeTraceBuildListenerCloseArgs> {

  private static final Logger LOG = Logger.get(ChromeTraceBuildListenerCloseAction.class);
  private static final int TIMEOUT_SECONDS = 30;

  /** Abstract class holding arguments to close() logic. */
  @BuckStyleValue
  public abstract static class ChromeTraceBuildListenerCloseArgs {

    public abstract ExecutorService getOutputExecutor();

    public abstract Path getTracePath();

    public abstract ChromeTraceWriter getChromeTraceWriter();

    public abstract OutputStream getTraceStream();

    public abstract ChromeTraceBuckConfig getConfig();

    public abstract Path getLogDirectoryPath();

    public abstract BuildId getBuildId();

    public abstract ProjectFilesystem getProjectFilesystem();
  }

  static Optional<Process> uploadTraceIfConfigured(
      BuildId buildId,
      ChromeTraceBuckConfig config,
      ProjectFilesystem projectFilesystem,
      Path tracePath,
      Path logDirectoryPath) {
    return config
        .getTraceUploadUriIfEnabled()
        .map(
            uri -> {
              Path fullPath = projectFilesystem.resolve(tracePath);
              Path logFile =
                  projectFilesystem.resolve(logDirectoryPath.resolve("upload-build-trace.log"));
              return launchUploader(buildId, uri, fullPath, logFile);
            });
  }

  @Nullable
  private static Process launchUploader(
      BuildId buildId, URI traceUploadUri, Path fullPath, Path logFile) {
    try {
      return UploaderLauncher.uploadInBackground(
          buildId, fullPath, TraceKind.BUILD_TRACE, traceUploadUri, logFile, CompressionType.GZIP);
    } catch (IOException e) {
      LOG.warn(e, "Can't launch uploader process");
    }
    return null;
  }

  @VisibleForTesting
  static void deleteOldTraces(
      ProjectFilesystem projectFilesystem, Path logDirectoryPath, ChromeTraceBuckConfig config) {
    if (!projectFilesystem.exists(logDirectoryPath)) {
      return;
    }

    Path traceDirectory = projectFilesystem.getPathForRelativePath(logDirectoryPath);

    try {
      for (Path path :
          PathListing.listMatchingPathsWithFilters(
              traceDirectory,
              "build.*.trace",
              PathListing.GET_PATH_MODIFIED_TIME,
              PathListing.FilterMode.EXCLUDE,
              OptionalInt.of(config.getMaxTraces()),
              Optional.empty())) {
        projectFilesystem.deleteFileAtPath(path);
      }
    } catch (IOException e) {
      LOG.error(e, "Couldn't list paths in trace directory %s", traceDirectory);
    }
  }

  @Override
  public void run(ChromeTraceBuildListenerCloseArgs args) throws IOException, InterruptedException {
    LOG.debug("Writing Chrome trace to %s", args.getTracePath());
    ExecutorService outputExecutor = args.getOutputExecutor();
    outputExecutor.shutdown();
    try {
      if (!outputExecutor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        LOG.warn("Failed to log buck trace %s.  Trace might be corrupt", args.getTracePath());
      }
    } catch (InterruptedException e) {
      Threads.interruptCurrentThread();
    }

    ChromeTraceWriter chromeTraceWriter = args.getChromeTraceWriter();
    chromeTraceWriter.writeEnd();
    chromeTraceWriter.close();
    args.getTraceStream().close();

    ProjectFilesystem projectFilesystem = args.getProjectFilesystem();
    ChromeTraceBuckConfig config = args.getConfig();
    Optional<Process> uploadProcess =
        uploadTraceIfConfigured(
            args.getBuildId(),
            config,
            projectFilesystem,
            args.getTracePath(),
            args.getLogDirectoryPath());

    String symlinkName = config.hasToCompressTraces() ? "build.trace.gz" : "build.trace";
    Path symlinkPath = projectFilesystem.getBuckPaths().getLogDir().resolve(symlinkName);
    projectFilesystem.createSymLink(
        projectFilesystem.resolve(symlinkPath),
        projectFilesystem.resolve(args.getTracePath()),
        true);

    deleteOldTraces(projectFilesystem, args.getLogDirectoryPath(), config);
    UploaderLauncher.maybeWaitForProcessToFinish(uploadProcess);
  }
}
