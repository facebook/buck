/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.rules.keys;

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyBuilderFactory;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.cache.FileHashCache;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/**
 * A variant of {@link InputBasedRuleKeyBuilderFactory} which ignores inputs when calculating the
 * {@link RuleKey}, allowing them to specified explicitly.
 */
public class DefaultDependencyFileRuleKeyBuilderFactory
    extends InputBasedRuleKeyBuilderFactory
    implements DependencyFileRuleKeyBuilderFactory {

  private final SourcePathResolver pathResolver;

  public DefaultDependencyFileRuleKeyBuilderFactory(
      FileHashCache fileHashCache,
      SourcePathResolver pathResolver,
      RuleKeyBuilderFactory ruleKeyBuilderFactory) {
    super(fileHashCache, pathResolver, ruleKeyBuilderFactory, InputHandling.IGNORE);
    this.pathResolver = pathResolver;
  }

  @Override
  public RuleKey build(BuildRule rule, ImmutableList<Path> inputs) throws IOException {

    // Create a builder which records all `SourcePath`s which are possibly used by the rule.
    Builder builder = newInstance(rule);

    // Use a multi-map to gather up all the `SourcePath`s that have relative paths that are
    // referenced in the input list, as it's possible for multiple `SourcePath`s to have the
    // same relative path (but come from different cells).
    ImmutableSet<Path> inputSet = ImmutableSet.copyOf(inputs);
    ImmutableMultimap.Builder<Path, SourcePath> relativePathToSourcePathsBuilder =
        ImmutableMultimap.builder();
    for (SourcePath input : builder.getInputsSoFar()) {
      Path relativePath = pathResolver.getRelativePath(input);
      if (inputSet.contains(relativePath)) {
        relativePathToSourcePathsBuilder.put(relativePath, input);
      }
    }
    final ImmutableMultimap<Path, SourcePath> relativePathToSourcePaths =
        relativePathToSourcePathsBuilder.build();

    // Now add the actual given inputs to the rule key using all possible `SourcePath`s they map to.
    // It's important that we do this by walking the `inputs` list, so that we maintain the original
    // ordering the duplicate handling.
    for (Path input : inputs) {
      ImmutableCollection<SourcePath> sourcePaths = relativePathToSourcePaths.get(input);

      // If we don't find actual inputs in the rule that correspond to this input, this likely means
      // that the rule changed to no longer use the input.  In this case, we need to throw a
      // `NoSuchFileException` error so that the build engine handles this as a signal that the dep
      // file rule key can't be used.
      if (sourcePaths.isEmpty()) {
        throw new NoSuchFileException(
            String.format(
                "%s: could not find any inputs matching the relative path `%s`",
                rule.getBuildTarget(),
                input));
      }

      // Add each `SourcePath` using `builder.setPath()`.  We can't use `builder.setSourcePath()`
      // here since the special `RuleKeyBuilder` sub-class that dep-file rule keys use intentionally
      // override `builder.setSourcePath()` to be a noop (and just record the inputs).
      for (SourcePath sourcePath : sourcePaths) {
        builder.setPath(
            pathResolver.getAbsolutePath(sourcePath),
            pathResolver.getRelativePath(sourcePath));
      }
    }

    return builder.build();
  }

}
