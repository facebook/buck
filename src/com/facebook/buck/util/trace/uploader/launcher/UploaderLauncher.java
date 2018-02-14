/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.util.trace.uploader.launcher;

import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.util.env.BuckClasspath;
import com.facebook.buck.util.trace.uploader.types.CompressionType;
import com.google.common.base.Strings;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

/** Utility to upload chrome trace in background. */
public class UploaderLauncher {
  private static final Logger LOG = Logger.get(UploaderLauncher.class);

  /** Upload chrome trace in background process which runs even after current process dies. */
  public static void uploadInBackground(
      BuildId buildId,
      Path traceFilePath,
      String traceFileKind,
      URI traceUploadUri,
      Path logFile,
      CompressionType compressionType) {

    LOG.debug("Uploading build trace in the background. Upload will log to %s", logFile);

    String buckClasspath = BuckClasspath.getBuckClasspathFromEnvVarOrNull();
    if (Strings.isNullOrEmpty(buckClasspath)) {
      LOG.error(
          BuckClasspath.ENV_VAR_NAME + " env var is not set. Will not upload the trace file.");
      return;
    }

    try {
      String[] args = {
        "java",
        "-cp",
        buckClasspath,
        "com.facebook.buck.util.trace.uploader.Main",
        "--buildId",
        buildId.toString(),
        "--traceFilePath",
        traceFilePath.toString(),
        "--traceFileKind",
        traceFileKind,
        "--baseUrl",
        traceUploadUri.toString(),
        "--log",
        logFile.toString(),
        "--compressionType",
        compressionType.name(),
      };

      Runtime.getRuntime().exec(args);
    } catch (IOException e) {
      LOG.error(e, e.getMessage());
    }
  }
}
