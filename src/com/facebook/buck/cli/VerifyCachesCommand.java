/*
 * Copyright 2016-present Facebook, Inc.
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

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.RuleKeyCacheRecycler;
import com.facebook.buck.rules.keys.RuleKeyFieldLoader;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.FileHashCacheVerificationResult;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Map;

/** Verify the contents of our FileHashCache. */
public class VerifyCachesCommand extends AbstractCommand {

  private boolean verifyFileHashCache(PrintStream stdOut, FileHashCache cache) throws IOException {
    FileHashCacheVerificationResult result = cache.verify();
    stdOut.println("Examined " + result.getCachesExamined() + " caches.");
    stdOut.println("Examined " + result.getFilesExamined() + " files.");
    if (result.getVerificationErrors().isEmpty()) {
      stdOut.println("No errors");
      return true;
    } else {
      stdOut.println("Errors detected:");
      for (String err : result.getVerificationErrors()) {
        stdOut.println("  " + err);
      }
      return false;
    }
  }

  private boolean verifyRuleKeyCache(
      PrintStream stdOut,
      int ruleKeySeed,
      FileHashCache fileHashCache,
      RuleKeyCacheRecycler<RuleKey> recycler) {
    ImmutableList<Map.Entry<BuildRule, RuleKey>> contents = recycler.getCachedBuildRules();
    RuleKeyFieldLoader fieldLoader = new RuleKeyFieldLoader(ruleKeySeed);
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    contents.forEach(e -> resolver.addToIndex(e.getKey()));
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    DefaultRuleKeyFactory defaultRuleKeyFactory =
        new DefaultRuleKeyFactory(fieldLoader, fileHashCache, pathResolver, ruleFinder);
    stdOut.println(String.format("Examining %d build rule keys.", contents.size()));
    ImmutableList<BuildRule> mismatches =
        RichStream.from(contents)
            .filter(entry -> !defaultRuleKeyFactory.build(entry.getKey()).equals(entry.getValue()))
            .map(Map.Entry::getKey)
            .toImmutableList();
    if (mismatches.isEmpty()) {
      stdOut.println("No rule key cache errors found.");
    } else {
      stdOut.println("Found rule key cache errors:");
      for (BuildRule rule : mismatches) {
        stdOut.println(String.format("  %s", rule));
      }
    }
    return true;
  }

  @Override
  public int runWithoutHelp(CommandRunnerParams params) throws IOException, InterruptedException {
    boolean success = true;

    // Verify file hash caches.
    params.getConsole().getStdOut().println("Verifying file hash caches...");
    success &= verifyFileHashCache(params.getConsole().getStdOut(), params.getFileHashCache());

    // Verify rule key caches.
    params.getConsole().getStdOut().println("Verifying rule key caches...");
    success &=
        params
            .getDefaultRuleKeyFactoryCacheRecycler()
            .map(
                recycler ->
                    verifyRuleKeyCache(
                        params.getConsole().getStdOut(),
                        params.getBuckConfig().getKeySeed(),
                        params.getFileHashCache(),
                        recycler))
            .orElse(true);

    return success ? 0 : 1;
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }

  @Override
  public String getShortDescription() {
    return "Verify contents of internal Buck in-memory caches.";
  }
}
