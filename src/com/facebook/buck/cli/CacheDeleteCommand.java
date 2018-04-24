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

package com.facebook.buck.cli;

import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.artifact_cache.CacheDeleteResult;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.ExitCode;
import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import org.kohsuke.args4j.Argument;

/** A command for deleting artifacts from cache. */
public class CacheDeleteCommand extends AbstractCommand {

  @Argument private List<String> arguments = new ArrayList<>();

  @VisibleForTesting
  void setArguments(List<String> arguments) {
    this.arguments = arguments;
  }

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params)
      throws IOException, InterruptedException {
    if (arguments.isEmpty()) {
      throw new CommandLineException("no cache keys specified");
    }

    List<RuleKey> ruleKeys = arguments.stream().map(RuleKey::new).collect(Collectors.toList());

    CacheDeleteResult deleteResult;
    try (ArtifactCache artifactCache = params.getArtifactCacheFactory().newInstance()) {
      try {
        // Wait for all executions to complete or fail.
        deleteResult = artifactCache.deleteAsync(ruleKeys).get();
      } catch (ExecutionException ex) {
        params.getConsole().printErrorText("Failed to delete artifacts.");
        ex.printStackTrace(params.getConsole().getStdErr());
        // Casting all errors to FATAL. Is there any possibility for user error, like missing cache
        // key?
        return ExitCode.FATAL_GENERIC;
      }
    }

    params
        .getConsole()
        .printSuccess(
            String.format(
                "Successfully deleted %s artifacts from %s caches: %s.",
                ruleKeys.size(),
                deleteResult.getCacheNames().size(),
                String.join(", ", deleteResult.getCacheNames())));
    return ExitCode.SUCCESS;
  }

  @Override
  public boolean isReadOnly() {
    return false;
  }

  @Override
  public String getShortDescription() {
    return "Delete artifacts from the local and remote cache";
  }
}
