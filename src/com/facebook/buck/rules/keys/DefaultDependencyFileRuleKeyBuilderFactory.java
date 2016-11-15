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

import com.facebook.buck.hashing.FileHashLoader;
import com.facebook.buck.model.Pair;
import com.facebook.buck.rules.ArchiveMemberSourcePath;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.Optional;

/**
 * A variant of {@link InputBasedRuleKeyBuilderFactory} which ignores inputs when calculating the
 * {@link RuleKey}, allowing them to specified explicitly.
 */
public class DefaultDependencyFileRuleKeyBuilderFactory
    extends InputBasedRuleKeyBuilderFactory
    implements DependencyFileRuleKeyBuilderFactory {

  private final SourcePathResolver pathResolver;

  public DefaultDependencyFileRuleKeyBuilderFactory(
      int seed,
      FileHashLoader fileHashLoader,
      SourcePathResolver pathResolver) {
    super(
        seed,
        fileHashLoader,
        pathResolver,
        InputHandling.IGNORE,
        ArchiveHandling.MEMBERS,
        Long.MAX_VALUE);
    this.pathResolver = pathResolver;
  }

  @Override
  public Optional<Pair<RuleKey, ImmutableSet<SourcePath>>> build(
      SupportsDependencyFileRuleKey rule,
      ImmutableList<DependencyFileEntry> depFileEntries) throws IOException {
    return new DependencyFileRuleKeyBuilder(rule)
        .build(rule.getPossibleInputSourcePaths(), depFileEntries);
  }

  @Override
  public Optional<Pair<RuleKey, ImmutableSet<SourcePath>>> buildManifestKey(
      SupportsDependencyFileRuleKey rule)
      throws IOException {
    return new ManifestKeyBuilder(rule)
        .build(rule.getPossibleInputSourcePaths());
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
    public Optional<Pair<RuleKey, ImmutableSet<SourcePath>>> build(
        Optional<ImmutableSet<SourcePath>> possibleDepFileSourcePaths) throws IOException {
      ImmutableSet<SourcePath> inputs = builder.getInputsSoFar();

      ImmutableSet<SourcePath> depFileInputs = inputs;

      if (possibleDepFileSourcePaths.isPresent()) {
        // possibleDepFileSourcePaths is an ImmutableSortedSet which implements contains() via
        // binary search rather than via hashing. Thus taking the intersection/difference
        // is O(n*log(n)). Here, we make a hash-based copy of the set, so that intersection
        // will be reduced to O(N).
        ImmutableSet<SourcePath> possibleDepFileSourcePathsUnsorted = ImmutableSet.copyOf(
            possibleDepFileSourcePaths.get()
        );
        Sets.SetView<SourcePath> nonDepFileInputs = Sets.difference(
            inputs,
            possibleDepFileSourcePathsUnsorted);

        builder.addToRuleKey(nonDepFileInputs);

        depFileInputs = ImmutableSet.copyOf(Sets.intersection(
            inputs,
            possibleDepFileSourcePathsUnsorted));
      }

      Optional<RuleKey> ruleKey = builder.build();
      if (ruleKey.isPresent()) {
        return Optional.of(new Pair<>(ruleKey.get(), depFileInputs));
      } else {
        return Optional.empty();
      }
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

    public Optional<Pair<RuleKey, ImmutableSet<SourcePath>>> build(
        Optional<ImmutableSet<SourcePath>> possibleDepFileSourcePaths,
        ImmutableList<DependencyFileEntry> depFileEntries) throws IOException {
      // TODO(jkeljo): Make this use possibleDepFileSourcePaths all the time
      Iterable<SourcePath> inputsSoFar = builder.getIterableInputsSoFar();
      // Dep file paths come as a sorted set, which does binary search instead of hashing for
      // lookups, so we re-create it as a hashset.
      ImmutableSet<SourcePath> fastPossibleSourcePaths =
          possibleDepFileSourcePaths.isPresent() ?
              ImmutableSet.copyOf(possibleDepFileSourcePaths.get()) :
              ImmutableSet.copyOf(inputsSoFar);

      ImmutableSet<DependencyFileEntry> depFileEntriesSet = ImmutableSet.copyOf(depFileEntries);

      final ImmutableSet.Builder<SourcePath> depFileSourcePathsBuilder = ImmutableSet.builder();
      final ImmutableSet.Builder<DependencyFileEntry> filesAccountedFor = ImmutableSet.builder();

      // Each input path falls into one of three categories:
      // 1) It's not covered by dep files, so we need to consider it part of the rule key
      // 2) It's covered by dep files and present in the dep file, so we consider it
      //    part of the rule key
      // 3) It's covered by dep files but not present in the dep file, so we don't include it in
      //    the rule key (the benefit of dep file support is that lots of things fall in this
      //    category, so we can avoid rebuilds that would have happened with input-based rule keys)
      for (SourcePath input : inputsSoFar) {
        if (!fastPossibleSourcePaths.contains(input)) {
          // If this path is not a dep file path, then add it to the builder directly
          builder.addToRuleKey(input);
        } else {
          // If this input path is covered by the dep paths, check to see if it was declared
          // as a real dependency by the dep file entries
          DependencyFileEntry entry = DependencyFileEntry.fromSourcePath(input, pathResolver);
          if (depFileEntriesSet.contains(entry)) {
            builder.addToRuleKey(input);
            depFileSourcePathsBuilder.add(input);
            filesAccountedFor.add(entry);
          }
        }
      }

      Sets.SetView<DependencyFileEntry> filesUnaccountedFor = Sets.difference(
          depFileEntriesSet, filesAccountedFor.build());
      // If we don't find actual inputs in one of the rules that corresponded to the input, this
      // likely means that the rule changed to no longer use the input. In this case we need to
      // throw a `NoSuchFileException` so that the build engine handles this as a signal that the
      // dep file rule key cannot be used.
      if (!filesUnaccountedFor.isEmpty()) {
        throw new NoSuchFileException(
            String.format(
                "%s: could not find any inputs matching the relative paths [%s]",
                rule.getBuildTarget(),
                Joiner.on(',').join(filesUnaccountedFor)));
      }

      Optional<RuleKey> ruleKey = builder.build();
      if (ruleKey.isPresent()) {
        return Optional.of(new Pair<>(ruleKey.get(), depFileSourcePathsBuilder.build()));
      } else {
        return Optional.empty();
      }
    }

  }

  private class BuilderWrapper {
    private final Builder builder;

    public BuilderWrapper(Builder builder) {
      this.builder = builder;
    }

    public Optional<RuleKey> build() {
      return builder.build();
    }

    public ImmutableSet<SourcePath> getInputsSoFar() {
      return builder.getInputsSoFar();
    }

    public Iterable<SourcePath> getIterableInputsSoFar() {
      return builder.getIterableInputsSoFar();
    }

    public void addToRuleKey(Iterable<SourcePath> sourcePaths) throws IOException {
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
