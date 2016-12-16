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
 * A variant of {@link InputBasedRuleKeyFactory} which ignores inputs when calculating the
 * {@link RuleKey}, allowing them to specified explicitly.
 */
public class DefaultDependencyFileRuleKeyFactory
    extends InputBasedRuleKeyFactory
    implements DependencyFileRuleKeyFactory {

  private final SourcePathResolver pathResolver;

  public DefaultDependencyFileRuleKeyFactory(
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
    // Create a builder which records all `SourcePath`s which are possibly used by the rule.
    Builder builder = newInstance(rule);

    Optional<ImmutableSet<SourcePath>> possibleDepFileSourcePaths =
        rule.getPossibleInputSourcePaths();
    // TODO(jkeljo): Make this use possibleDepFileSourcePaths all the time
    Iterable<SourcePath> inputsSoFar = builder.getIterableInputsSoFar();
    // Dep file paths come as a sorted set, which does binary search instead of hashing for
    // lookups, so we re-create it as a hashset.
    ImmutableSet<SourcePath> fastPossibleSourcePaths =
        possibleDepFileSourcePaths.isPresent() ?
            ImmutableSet.copyOf(possibleDepFileSourcePaths.get()) :
            ImmutableSet.of();

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
      if (possibleDepFileSourcePaths.isPresent() && !fastPossibleSourcePaths.contains(input)) {
        // If this path is not a dep file path, then add it to the builder directly.
        // Note that if {@code possibleDepFileSourcePaths} is not present, we treat
        // all the input files as covered by dep file, so we'll never end up here!
        addSourcePathToRuleKey(builder, input);

      } else {
        // If this input path is covered by the dep paths, check to see if it was declared
        // as a real dependency by the dep file entries
        DependencyFileEntry entry = DependencyFileEntry.fromSourcePath(input, pathResolver);
        if (depFileEntriesSet.contains(entry)) {
          addSourcePathToRuleKey(builder, input);
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

  @Override
  public Optional<Pair<RuleKey, ImmutableSet<SourcePath>>> buildManifestKey(
      SupportsDependencyFileRuleKey rule)
      throws IOException {
    Builder builder = newInstance(rule);
    builder.setReflectively("buck.key_type", "manifest");

    ImmutableSet<SourcePath> inputs = builder.getInputsSoFar();
    Optional<ImmutableSet<SourcePath>> possibleDepFileSourcePaths =
        rule.getPossibleInputSourcePaths();

    final ImmutableSet<SourcePath> depFileInputs;
    if (possibleDepFileSourcePaths.isPresent()) {
      // possibleDepFileSourcePaths is an ImmutableSortedSet which implements contains() via
      // binary search rather than via hashing. Thus taking the intersection/difference
      // is O(n*log(n)). Here, we make a hash-based copy of the set, so that intersection
      // will be reduced to O(N).
      ImmutableSet<SourcePath> possibleDepFileSourcePathsUnsorted =
          ImmutableSet.copyOf(possibleDepFileSourcePaths.get());
      Sets.SetView<SourcePath> nonDepFileInputs = Sets.difference(
          inputs,
          possibleDepFileSourcePathsUnsorted);

      for (SourcePath input : nonDepFileInputs) {
        addSourcePathToRuleKey(builder, input);
      }

      depFileInputs = ImmutableSet.copyOf(Sets.intersection(
          inputs,
          possibleDepFileSourcePathsUnsorted));
    } else {
      // If not present, we treat all the input files as covered by dep file.
      depFileInputs = inputs;
    }

    Optional<RuleKey> ruleKey = builder.build();
    if (ruleKey.isPresent()) {
      return Optional.of(new Pair<>(ruleKey.get(), depFileInputs));
    } else {
      return Optional.empty();
    }
  }

  private void addSourcePathToRuleKey(Builder builder, SourcePath sourcePath) throws IOException {
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
}
