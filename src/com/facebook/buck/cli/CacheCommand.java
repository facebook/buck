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

import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.rules.CacheResult;
import com.facebook.buck.rules.CassandraArtifactCache;
import com.facebook.buck.rules.RuleKey;
import com.google.common.io.Files;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * A command for inspecting the artifact cache. For now, this is hardcoded to only query the
 * {@link CassandraArtifactCache}, assuming the user has one defined in {@code .buckconfig}.
 */
public class CacheCommand extends AbstractCommandRunner<CacheCommandOptions> {

  protected CacheCommand(CommandRunnerParams params) {
    super(params);
  }

  @Override
  CacheCommandOptions createOptions(BuckConfig buckConfig) {
    return new CacheCommandOptions(buckConfig);
  }

  @Override
  int runCommandWithOptionsInternal(CacheCommandOptions options)
      throws IOException, InterruptedException {

    if (options.isNoCache()) {
      console.printErrorText("Caching is disabled.");
      return 1;
    }

    List<String> arguments = options.getArguments();
    if (arguments.isEmpty()) {
      console.printErrorText("No cache keys specified.");
      return 1;
    }

    ArtifactCache cache = getArtifactCache();

    File tmpDir = Files.createTempDir();
    int exitCode = 0;
    for (String arg : arguments) {
      // Do the fetch.
      RuleKey ruleKey = new RuleKey(arg);
      File artifact = new File(tmpDir, arg);
      CacheResult success = cache.fetch(ruleKey, artifact);

      // Display the result.
      if (success.isSuccess()) {
        console.printSuccess(String.format(
            "Successfully downloaded artifact with id %s at %s.",
            ruleKey,
            artifact));
      } else {
        console.printErrorText(String.format(
            "Failed to retrieve an artifact with id %s.", ruleKey));
        exitCode = 1;
      }
    }

    return exitCode;
  }

  @Override
  String getUsageIntro() {
    return "Inspect the artifact cache.";
  }

}
