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
import com.facebook.buck.event.chrome_trace.ChromeTraceBuckConfig;
import com.facebook.buck.event.chrome_trace.ChromeTraceWriter;
import com.facebook.buck.io.file.PathListing;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.support.bgtasks.TaskAction;
import com.facebook.buck.util.Threads;
import com.facebook.buck.util.trace.uploader.launcher.UploaderLauncher;
import com.facebook.buck.util.trace.uploader.types.CompressionType;
import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.immutables.value.Value;

/** TaskAction implementation for the close() logic in {@link ChromeTraceBuildListener}. */
class ChromeTraceBuildListenerCloseAction implements TaskAction<ChromeTraceBuildListenerCloseArgs> {

  private static final Logger LOG = Logger.get(ChromeTraceBuildListenerCloseAction.class);
  private static final int TIMEOUT_SECONDS = 30;

  /** Abstract class holding arguments to close() logic. */
  @Value.Immutable(builder = false)
  @BuckStyleImmutable
  public abstract static class AbstractChromeTraceBuildListenerCloseArgs {
    @Value.Parameter
    public abstract ExecutorService getOutputExecutor();

    @Value.Parameter
    public abstract Path getTracePath();

    @Value.Parameter
    public abstract ChromeTraceWriter getChromeTraceWriter();

    @Value.Parameter
    public abstract OutputStream getTraceStream();

    @Value.Parameter
    public abstract ChromeTraceBuckConfig getConfig();

    @Value.Parameter
    public abstract Path getLogDirectoryPath();

    @Value.Parameter
    public abstract BuildId getBuildId();

    @Value.Parameter
    public abstract ProjectFilesystem getProjectFilesystem();
  }

  static void uploadTraceIfConfigured(
      BuildId buildId,
      ChromeTraceBuckConfig config,
      ProjectFilesystem projectFilesystem,
      Path tracePath,
      Path logDirectoryPath) {
    Optional<URI> traceUploadUri = config.getTraceUploadUriIfEnabled();
    if (!traceUploadUri.isPresent()) {
      return;
    }

    Path fullPath = projectFilesystem.resolve(tracePath);
    Path logFile = projectFilesystem.resolve(logDirectoryPath.resolve("upload-build-trace.log"));

    UploaderLauncher.uploadInBackground(
        buildId, fullPath, "default", traceUploadUri.get(), logFile, CompressionType.GZIP);
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
  public void run(ChromeTraceBuildListenerCloseArgs args) throws IOException {
    LOG.debug("Writing Chrome trace to %s", args.getTracePath());
    args.getOutputExecutor().shutdown();
    try {
      if (!args.getOutputExecutor().awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        LOG.warn("Failed to log buck trace %s.  Trace might be corrupt", args.getTracePath());
      }
    } catch (InterruptedException e) {
      Threads.interruptCurrentThread();
    }

    args.getChromeTraceWriter().writeEnd();
    args.getChromeTraceWriter().close();
    args.getTraceStream().close();

    uploadTraceIfConfigured(
        args.getBuildId(),
        args.getConfig(),
        args.getProjectFilesystem(),
        args.getTracePath(),
        args.getLogDirectoryPath());

    String symlinkName = args.getConfig().getCompressTraces() ? "build.trace.gz" : "build.trace";
    Path symlinkPath = args.getProjectFilesystem().getBuckPaths().getLogDir().resolve(symlinkName);
    args.getProjectFilesystem()
        .createSymLink(
            args.getProjectFilesystem().resolve(symlinkPath),
            args.getProjectFilesystem().resolve(args.getTracePath()),
            true);

    deleteOldTraces(args.getProjectFilesystem(), args.getLogDirectoryPath(), args.getConfig());
  }
}
