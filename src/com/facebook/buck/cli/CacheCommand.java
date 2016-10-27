/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.cli;

import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.io.LazyPath;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.rules.BuildInfo;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.zip.Unzip;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;

import javax.annotation.Nullable;

/**
 *
 * A command for inspecting the artifact cache.
 */
public class CacheCommand extends AbstractCommand {

  @Argument
  private List<String> arguments = Lists.newArrayList();

  @Option(
      name = "--output-dir",
      usage = "Extract artifacts to this directory.")
  @Nullable
  private String outputDir = null;

  public List<String> getArguments() {
    return arguments;
  }

  @VisibleForTesting
  void setArguments(List<String> arguments) {
    this.arguments = arguments;
  }

  @Override
  public int runWithoutHelp(CommandRunnerParams params) throws IOException, InterruptedException {

    params.getBuckEventBus().post(ConsoleEvent.fine("cache command start"));

    if (isNoCache()) {
      params.getBuckEventBus().post(ConsoleEvent.severe("Caching is disabled."));
      return 1;
    }

    List<String> arguments = getArguments();
    if (arguments.isEmpty()) {
      params.getBuckEventBus().post(ConsoleEvent.severe("No cache keys specified."));
      return 1;
    }

    Path outputPath = null;
    if (outputDir != null) {
      outputPath = Paths.get(outputDir);
      Files.createDirectories(outputPath);
    }

    ArtifactCache cache = params.getArtifactCache();

    Path tmpDir = Files.createTempDirectory("buck-cache-command");
    int exitCode = 0;
    for (String arg : arguments) {
      // Do the fetch.
      RuleKey ruleKey = new RuleKey(arg);
      Path artifact = tmpDir.resolve(arg);
      // TODO(skotchvail): don't use intermediate files, that just slows us down
      // instead, unzip from the ~/buck-cache/ directly
      CacheResult success = cache.fetch(ruleKey, LazyPath.ofInstance(artifact));

      // Display the result.
      if (outputPath != null) {
        if (!extractArtifact(
            params,
            outputPath,
            tmpDir,
            ruleKey,
            artifact,
            success)) {
          exitCode = 1;
        }
      } else if (success.getType().isSuccess()) {
        params.getConsole().printSuccess(
            String.format(
                "Successfully downloaded artifact with id %s at %s.",
                ruleKey,
                artifact));
      } else {
        params.getConsole().printErrorText(
            String.format(
                "Failed to retrieve an artifact with id %s.", ruleKey));
        exitCode = 1;
      }
    }

    if (outputPath != null) {
      if (exitCode == 0) {
        params.getConsole().printSuccess(
          "Successfully downloaded all artifacts.");
      } else {
        params.getConsole().printErrorText(
          "Failed to download all artifacts");
      }
    }

    return exitCode;
  }

  private boolean extractArtifact(
      CommandRunnerParams params,
      Path outputPath,
      Path tmpDir,
      RuleKey ruleKey,
      Path artifact,
      CacheResult success) {

    PrintStream stdOut = params.getConsole().getStdOut();
    if (!success.getType().isSuccess()) {
      stdOut.println(String.format(
          "%s !(Failed to retrieve an artifact)", ruleKey));
      return false;
    }

    String buckTarget = success.metadata().get().get(BuildInfo.METADATA_KEY_FOR_TARGET);
    ImmutableList<Path> paths;
    try {
      paths = Unzip.extractZipFile(
          artifact.toAbsolutePath(),
          tmpDir,
          Unzip.ExistingFileMode.OVERWRITE_AND_CLEAN_DIRECTORIES);
    } catch (IOException e) {
      stdOut.println(String.format(
        "%s %s !(Unable to extract) %s.", ruleKey, buckTarget, e));
      return false;
    }
    int filesMoved = 0;
    for (Path path : paths) {
      if (path.getParent().getFileName().toString().equals("metadata")) {
        continue;
      }
      Path relative = tmpDir.relativize(path);
      Path destination = outputPath.resolve(relative);
      try {
        Files.createDirectories(destination.getParent());
        Files.move(path, destination, StandardCopyOption.ATOMIC_MOVE);
        stdOut.println(String.format(
            "%s %s => %s", ruleKey, buckTarget, relative));
        filesMoved += 1;
      } catch (IOException e) {
        stdOut.println(String.format(
            "%s %s !(could not move file) %s", ruleKey, buckTarget, relative));
        return false;
      }
    }
    if (filesMoved == 0) {
      stdOut.println(String.format(
          "%s %s !(Nothing to extract)", ruleKey, buckTarget));
    }
    return filesMoved > 0;
  }

  @Override
  public String getShortDescription() {
    return "makes calls to the artifact cache";
  }

  @Override
  public boolean isReadOnly() {
    return false;
  }

}
