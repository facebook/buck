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
import com.facebook.buck.artifact_cache.CacheResultType;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.LazyPath;
import com.facebook.buck.parser.ParseEvent;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.BuildInfo;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.util.concurrent.WeightedListeningExecutorService;
import com.facebook.buck.zip.Unzip;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import javax.annotation.Nullable;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

/** A command for inspecting the artifact cache. */
public class CacheCommand extends AbstractCommand {

  @Argument private List<String> arguments = new ArrayList<>();

  @Option(name = "--output-dir", usage = "Extract artifacts to this directory.")
  @Nullable
  private String outputDir = null;

  public List<String> getArguments() {
    return arguments;
  }

  Optional<Path> outputPath = Optional.empty();

  @VisibleForTesting
  void setArguments(List<String> arguments) {
    this.arguments = arguments;
  }

  public void fakeOutParseEvents(BuckEventBus eventBus) {
    ParseEvent.Started parseStart = ParseEvent.started(ImmutableList.of());
    eventBus.post(parseStart);
    eventBus.post(ParseEvent.finished(parseStart, 0, Optional.empty()));
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

    if (outputDir != null) {
      outputPath = Optional.of(Paths.get(outputDir));
      Files.createDirectories(outputPath.get());
    }

    List<RuleKey> ruleKeys = new ArrayList<>();
    for (String hash : arguments) {
      ruleKeys.add(new RuleKey(hash));
    }

    Path tmpDir = Files.createTempDirectory("buck-cache-command");

    BuildEvent.Started started = BuildEvent.started(getArguments());

    List<ArtifactRunner> results = null;
    try (ArtifactCache cache = params.getArtifactCacheFactory().newInstance();
        CommandThreadManager pool =
            new CommandThreadManager("Build", getConcurrencyLimit(params.getBuckConfig()))) {
      WeightedListeningExecutorService executor = pool.getExecutor();

      fakeOutParseEvents(params.getBuckEventBus());

      // Post the build started event, setting it to the Parser recorded start time if appropriate.
      if (params.getParser().getParseStartTime().isPresent()) {
        params.getBuckEventBus().post(started, params.getParser().getParseStartTime().get());
      } else {
        params.getBuckEventBus().post(started);
      }

      // Fetch all artifacts
      List<ListenableFuture<ArtifactRunner>> futures = new ArrayList<>();
      for (RuleKey ruleKey : ruleKeys) {
        futures.add(executor.submit(new ArtifactRunner(ruleKey, tmpDir, cache)));
      }

      // Wait for all executions to complete or fail.
      try {
        results = Futures.allAsList(futures).get();
      } catch (ExecutionException ex) {
        params.getConsole().printBuildFailure("Failed");
        ex.printStackTrace(params.getConsole().getStdErr());
      }
    }

    int totalRuns = results.size();
    String resultString = "";
    int goodRuns = 0;
    for (ArtifactRunner r : results) {
      if (r.completed) {
        goodRuns++;
      }
      resultString += r.resultString;

      if (!outputPath.isPresent()) {
        // legacy output
        if (r.completed) {
          params
              .getConsole()
              .printSuccess(
                  String.format(
                      "Successfully downloaded artifact with id %s at %s .",
                      r.ruleKey, r.artifact));
        } else {
          params
              .getConsole()
              .printErrorText(
                  String.format("Failed to retrieve an artifact with id %s.", r.ruleKey));
        }
      }
    }

    int exitCode = (totalRuns == goodRuns) ? 0 : 1;
    params.getBuckEventBus().post(BuildEvent.finished(started, exitCode));

    if (outputPath.isPresent()) {
      if (totalRuns == goodRuns) {
        params.getConsole().printSuccess("Successfully downloaded all artifacts.");
      } else {
        params
            .getConsole()
            .printErrorText(String.format("Downloaded %d of %d artifacts", goodRuns, totalRuns));
      }
      params.getConsole().getStdOut().println(resultString);
    }

    return exitCode;
  }

  private String cacheResultToString(CacheResult cacheResult) {
    CacheResultType type = cacheResult.getType();
    String typeString = type.toString();
    switch (type) {
      case ERROR:
        return String.format("%s %s", typeString, cacheResult.getCacheError());
      case HIT:
        return String.format("%s %s", typeString, cacheResult.getCacheSource());
      case MISS:
      case IGNORED:
      case LOCAL_KEY_UNCHANGED_HIT:
      default:
        return typeString;
    }
  }

  private boolean extractArtifact(
      Path outputPath,
      Path tmpDir,
      RuleKey ruleKey,
      Path artifact,
      CacheResult success,
      StringBuilder resultString)
      throws InterruptedException {

    String buckTarget = "Unknown Target";
    ImmutableMap<String, String> metadata = success.getMetadata();
    if (metadata.containsKey(BuildInfo.MetadataKey.TARGET)) {
      buckTarget = success.metadata().get().get(BuildInfo.MetadataKey.TARGET);
    }
    ImmutableList<Path> paths;
    try {
      paths =
          Unzip.extractZipFile(
              artifact.toAbsolutePath(),
              tmpDir,
              Unzip.ExistingFileMode.OVERWRITE_AND_CLEAN_DIRECTORIES);
    } catch (IOException e) {
      resultString.append(String.format("%s %s !(Unable to extract) %s\n", ruleKey, buckTarget, e));
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
        resultString.append(String.format("%s %s => %s\n", ruleKey, buckTarget, relative));
        filesMoved += 1;
      } catch (IOException e) {
        resultString.append(
            String.format("%s %s !(could not move file) %s\n", ruleKey, buckTarget, relative));
        return false;
      }
    }
    if (filesMoved == 0) {
      resultString.append(String.format("%s %s !(Nothing to extract)\n", ruleKey, buckTarget));
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

  class ArtifactRunner implements Callable<ArtifactRunner> {

    RuleKey ruleKey;
    Path tmpDir;
    Path artifact;
    String statusString;
    String cacheResult;
    StringBuilder resultString;
    ArtifactCache cache;
    boolean completed;

    public ArtifactRunner(RuleKey ruleKey, Path tmpDir, ArtifactCache cache) {
      this.ruleKey = ruleKey;
      this.tmpDir = tmpDir;
      this.cache = cache;
      this.artifact = tmpDir.resolve(ruleKey.toString());
      this.statusString = "Created";
      this.cacheResult = "Unknown";
      this.resultString = new StringBuilder();
      this.completed = false;
    }

    @Override
    public String toString() {
      return String.format("ruleKey: %s status: %s cache: %s", ruleKey, statusString, cacheResult);
    }

    @Override
    public ArtifactRunner call() throws InterruptedException {
      statusString = "Fetching";
      // TODO(skotch): don't use intermediate files, that just slows us down
      // instead, unzip from the ~/buck-cache/ directly
      CacheResult success = cache.fetch(ruleKey, LazyPath.ofInstance(artifact));
      cacheResult = cacheResultToString(success);
      boolean cacheSuccess = success.getType().isSuccess();
      if (!cacheSuccess) {
        statusString = String.format("FAILED FETCHING %s %s", ruleKey, cacheResult);
        resultString.append(String.format("%s !(Failed to retrieve an artifact)\n", ruleKey));
      } else if (!outputPath.isPresent()) {
        this.completed = true;
        statusString = "SUCCESS";
        resultString.append(String.format("%s !success\n", ruleKey));
      } else {
        statusString = "Extracting";
        if (extractArtifact(
            outputPath.get(), tmpDir, ruleKey, artifact, success, this.resultString)) {
          this.completed = true;
          statusString = "SUCCESS";
        } else {
          statusString = "FAILED Extracting";
        }
      }
      return this;
    }
  }
}
