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

import com.facebook.buck.model.Pair;
import com.facebook.buck.rules.ArchiveMemberSourcePath;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.cache.FileHashCache;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import java.io.IOException;
import java.nio.file.NoSuchFileException;

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
      SourcePathResolver pathResolver) {
    super(fileHashCache, pathResolver, InputHandling.IGNORE);
    this.pathResolver = pathResolver;
  }

  @Override
  public Pair<RuleKey, ImmutableSet<SourcePath>> build(
      BuildRule rule,
      Optional<ImmutableSet<SourcePath>> possibleDepFileSourcePaths,
      ImmutableList<DependencyFileEntry> depFileEntries) throws IOException {
    return new DependencyFileRuleKeyBuilder(rule).build(possibleDepFileSourcePaths, depFileEntries);
  }

  @Override
  public Pair<RuleKey, ImmutableSet<SourcePath>> buildManifestKey(BuildRule rule)
      throws IOException {
    SupportsDependencyFileRuleKey depFileSupportingRule = (SupportsDependencyFileRuleKey) rule;

    return new ManifestKeyBuilder(rule).build(depFileSupportingRule.getPossibleInputSourcePaths());
  }

  private class ManifestKeyBuilder {
    private final BuilderWrapper builder;

    public ManifestKeyBuilder(BuildRule rule) {
      builder = new BuilderWrapper(newInstance(rule));
      builder.setReflectively("buck.key_type", "manifest");
    }

    /**
     * @return Pair of dep-file rule key and the members of possibleDepFileSourcePaths that actually
     *         appeared in the dep file
     * @throws IOException
     */
    public Pair<RuleKey, ImmutableSet<SourcePath>> build(
        Optional<ImmutableSet<SourcePath>> possibleDepFileSourcePaths) throws IOException {
      ImmutableSet<SourcePath> inputs = builder.getInputsSoFar();

      ImmutableSet<SourcePath> depFileInputs = inputs;

      if (possibleDepFileSourcePaths.isPresent()) {
        Sets.SetView<SourcePath> nonDepFileInputs = Sets.difference(
            inputs,
            possibleDepFileSourcePaths.get());

        builder.addToRuleKey(ImmutableSet.copyOf(nonDepFileInputs));

        depFileInputs = ImmutableSet.copyOf(Sets.intersection(
            inputs,
            possibleDepFileSourcePaths.get()));
      }

      return new Pair<>(builder.build(), depFileInputs);
    }
  }


  private class DependencyFileRuleKeyBuilder {
    private final BuilderWrapper builder;
    private final BuildRule rule;

    public DependencyFileRuleKeyBuilder(BuildRule rule) {
      this.rule = rule;

      // Create a builder which records all `SourcePath`s which are possibly used by the rule.
      builder = new BuilderWrapper(newInstance(rule));
    }

    public Pair<RuleKey, ImmutableSet<SourcePath>> build(
        Optional<ImmutableSet<SourcePath>> possibleDepFileSourcePaths,
        ImmutableList<DependencyFileEntry> depFileEntries) throws IOException {

      ImmutableList<SourcePath> nonDepFileSourcePaths =
          getNonDepFileSourcePaths(possibleDepFileSourcePaths);
      ImmutableList<SourcePath> depFileSourcePaths =
          getDepFileSourcePaths(depFileEntries, possibleDepFileSourcePaths);

      builder.addToRuleKey(nonDepFileSourcePaths);
      builder.addToRuleKey(depFileSourcePaths);

      return new Pair<>(builder.build(), ImmutableSet.copyOf(depFileSourcePaths));
    }

    private ImmutableList<SourcePath> getNonDepFileSourcePaths(
        Optional<ImmutableSet<SourcePath>> possibleDepFileSourcePaths) {
      ImmutableList.Builder<SourcePath> sourcePathsBuilder = ImmutableList.builder();
      if (possibleDepFileSourcePaths.isPresent()) {
        for (SourcePath input : builder.getInputsSoFar()) {
          // Add any inputs that aren't subject to filtering by the dependency file, so that they
          // get added to the rule key.
          if (!possibleDepFileSourcePaths.get().contains(input)) {
            sourcePathsBuilder.add(input);
          }
        }
      }
      return sourcePathsBuilder.build();
    }

    private ImmutableList<SourcePath> getDepFileSourcePaths(
        ImmutableList<DependencyFileEntry> depFileEntries,
        Optional<ImmutableSet<SourcePath>> possibleDepFileSourcePaths) throws NoSuchFileException {
      final ImmutableMultimap<DependencyFileEntry, SourcePath> entryToSourcePaths =
          getDepFileEntryToSourcePathMap(depFileEntries, possibleDepFileSourcePaths);

      final ImmutableList.Builder<SourcePath> sourcePathsBuilder = ImmutableList.builder();
      // Now add the actual given inputs to the rule key using all possible `SourcePath`s they map
      // to.
      for (DependencyFileEntry input : depFileEntries) {
        ImmutableCollection<SourcePath> sourcePaths = entryToSourcePaths.get(input);

        // If we don't find actual inputs in the rule that correspond to this input, this likely
        // means that the rule changed to no longer use the input.  In this case, we need to throw a
        // `NoSuchFileException` error so that the build engine handles this as a signal that the
        // dep file rule key can't be used.
        if (sourcePaths.isEmpty()) {
          throw new NoSuchFileException(
              String.format(
                  "%s: could not find any inputs matching the relative path `%s`",
                  rule.getBuildTarget(),
                  input));
        }

        sourcePathsBuilder.addAll(sourcePaths);
      }

      return sourcePathsBuilder.build();
    }

    private ImmutableMultimap<DependencyFileEntry, SourcePath> getDepFileEntryToSourcePathMap(
        ImmutableList<DependencyFileEntry> depFileEntries,
        Optional<ImmutableSet<SourcePath>> possibleDepFileSourcePaths) {

      // Use a multi-map to gather up all the `SourcePath`s that have relative URIs that are
      // referenced in the input list, as it's possible for multiple `SourcePath`s to have the
      // same relative URI (but come from different cells).

      // TODO(jkeljo): Make this use possibleDepFileSourcePaths all the time
      ImmutableSet<SourcePath> possibleSourcePaths =
          possibleDepFileSourcePaths.isPresent() ?
              possibleDepFileSourcePaths.get() :
              builder.getInputsSoFar();

      ImmutableSet<DependencyFileEntry> inputSet = ImmutableSet.copyOf(depFileEntries);

      ImmutableMultimap.Builder<DependencyFileEntry, SourcePath> entryToSourcePathsBuilder =
          ImmutableMultimap.builder();
      for (SourcePath input : possibleSourcePaths) {
        DependencyFileEntry entry = DependencyFileEntry.fromSourcePath(input, pathResolver);
        if (inputSet.contains(entry)) {
          entryToSourcePathsBuilder.put(entry, input);
        }
      }
      return entryToSourcePathsBuilder.build();
    }
  }

  private class BuilderWrapper {
    private final Builder builder;

    public BuilderWrapper(Builder builder) {
      this.builder = builder;
    }

    public RuleKey build() {
      return builder.build();
    }

    public ImmutableSet<SourcePath> getInputsSoFar() {
      return builder.getInputsSoFar();
    }

    public void addToRuleKey(ImmutableCollection<SourcePath> sourcePaths) throws IOException {
      for (SourcePath sourcePath : sourcePaths) {
        addToRuleKey(sourcePath);
      }
    }

    public void addToRuleKey(SourcePath sourcePath) throws IOException {
      // Add each `SourcePath` using `builder.setPath()`.  We can't use `builder.setSourcePath()`
      // here since the special `RuleKeyBuilder` sub-class that dep-file rule keys use intentionally
      // override `builder.setSourcePath()` to be a noop (and just record the inputs).
      if (sourcePath instanceof ArchiveMemberSourcePath) {
        builder.setArchiveMemberPath(
            pathResolver.getAbsoluteArchiveMemberPath(sourcePath),
            pathResolver.getRelativeArchiveMemberPath(sourcePath));
      } else {
        builder.setPath(
            pathResolver.getAbsolutePath(sourcePath),
            pathResolver.getRelativePath(sourcePath));
      }
    }

    public BuilderWrapper setReflectively(String key, Object value) {
      builder.setReflectively(key, value);
      return this;
    }
  }
}
