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
import com.facebook.buck.rules.RuleKey;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.common.io.Files;

import org.kohsuke.args4j.Argument;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * A command for inspecting the artifact cache.
 */
public class CacheCommand extends AbstractCommand {

  @Argument
  private List<String> arguments = Lists.newArrayList();

  public List<String> getArguments() {
    return arguments;
  }

  @VisibleForTesting
  void setArguments(List<String> arguments) {
    this.arguments = arguments;
  }

  @Override
  public int runWithoutHelp(CommandRunnerParams params) throws IOException, InterruptedException {

    if (isNoCache()) {
      params.getConsole().printErrorText("Caching is disabled.");
      return 1;
    }

    List<String> arguments = getArguments();
    if (arguments.isEmpty()) {
      params.getConsole().printErrorText("No cache keys specified.");
      return 1;
    }

    ArtifactCache cache = getArtifactCache(params);

    File tmpDir = Files.createTempDir();
    int exitCode = 0;
    for (String arg : arguments) {
      // Do the fetch.
      RuleKey ruleKey = new RuleKey(arg);
      File artifact = new File(tmpDir, arg);
      CacheResult success = cache.fetch(ruleKey, artifact);

      // Display the result.
      if (success.getType().isSuccess()) {
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

    return exitCode;
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
