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

package com.facebook.buck.util.cmd;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.util.environment.Platform;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * Wrapper for an executable paths with spaces on Windows. This class helps to avoid wrapping paths
 * with spaces for generated cmd genrules.
 */
public class CmdWrapperForScriptWithSpaces {

  private static final Logger LOG = Logger.get(CmdWrapperForScriptWithSpaces.class);
  private static final Path TMP_DIR = Paths.get(System.getProperty("java.io.tmpdir"));

  private final Optional<Path> cmdPath;

  public CmdWrapperForScriptWithSpaces(Path path) {
    Preconditions.checkArgument(
        Platform.detect() == Platform.WINDOWS, "Cmd wrapper has to be used only on Windows");
    Preconditions.checkArgument(
        path.toString().contains(" "), "Cmd wrapper has to be used only for paths with spaces");
    this.cmdPath = wrapPathIntoCmdScript(path);
  }

  public Optional<Path> getCmdPath() {
    return cmdPath;
  }

  @VisibleForTesting
  static Optional<Path> wrapPathIntoCmdScript(Path pathToExecutable) {
    return wrapPathIntoCmdScript(pathToExecutable, TMP_DIR);
  }

  @VisibleForTesting
  static Optional<Path> wrapPathIntoCmdScript(Path pathToExecutable, Path tempFileDirectory) {
    try {
      Path cmdPath = Files.createTempFile(tempFileDirectory, "wrapper", ".cmd");
      // if temp path contains spaces
      if (cmdPath.toString().contains(" ")) {
        return Optional.empty();
      }

      // escaped path with spaces + all parameters passed to it.
      String scriptBody = "\"" + pathToExecutable + "\" %*";
      Files.write(cmdPath, scriptBody.getBytes(StandardCharsets.UTF_8));
      return Optional.of(cmdPath);
    } catch (IOException e) {
      LOG.warn(e, "Cannot write path: '%s' into cmd script", pathToExecutable);
      return Optional.empty();
    }
  }
}
