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
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.google.common.base.Joiner;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.Collections;
import java.util.Optional;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A factory for generating dependency-file {@link RuleKey}s.
 */
public final class DefaultDependencyFileRuleKeyFactory
    extends ReflectiveRuleKeyFactory<DefaultDependencyFileRuleKeyFactory.Builder, RuleKey>
    implements DependencyFileRuleKeyFactory {

  private final FileHashLoader fileHashLoader;
  private final SourcePathResolver pathResolver;
  private final SourcePathRuleFinder ruleFinder;
  private final LoadingCache<RuleKeyAppendable, Result> cache;

  public DefaultDependencyFileRuleKeyFactory(
      int seed,
      FileHashLoader hashLoader,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder) {
    super(seed);
    this.fileHashLoader = hashLoader;
    this.pathResolver = pathResolver;
    this.ruleFinder = ruleFinder;

    // Build the cache around the sub-rule-keys and their dep lists.
    cache = CacheBuilder.newBuilder().weakKeys().build(
        new CacheLoader<RuleKeyAppendable, Result>() {
          @Override
          public Result load(
              @Nonnull RuleKeyAppendable appendable) {
            Builder subKeyBuilder = new Builder();
            appendable.appendToRuleKey(subKeyBuilder);
            return subKeyBuilder.buildResult();
          }
        });
  }

  @Override
  protected Builder newBuilder(final BuildRule rule) {
    return new Builder();
  }

  class Builder extends RuleKeyBuilder<RuleKey> {

    private final ImmutableList.Builder<Iterable<SourcePath>> inputs = ImmutableList.builder();

    private Builder() {
      super(ruleFinder, pathResolver, fileHashLoader);
    }

    @Override
    protected Builder setAppendableRuleKey(RuleKeyAppendable appendable) {
      Result result = cache.getUnchecked(appendable);
      inputs.add(result.getInputs());
      setAppendableRuleKey(result.getRuleKey());
      return this;
    }

    @Override
    protected Builder setReflectively(@Nullable Object val) {
      if (val instanceof ArchiveDependencySupplier) {
        Object members = ((ArchiveDependencySupplier) val).getArchiveMembers(pathResolver);
        super.setReflectively(members);
      } else {
        super.setReflectively(val);
      }
      return this;
    }

    @Override
    protected Builder setSourcePath(SourcePath sourcePath) {
      inputs.add(Collections.singleton(sourcePath));
      return this;
    }

    // Rules supporting dep-file rule keys should be described entirely by their `SourcePath`
    // inputs.  If we see a `BuildRule` when generating the rule key, this is likely a break in
    // that contract, so check for that.
    @Override
    protected Builder setBuildRule(BuildRule rule) {
      throw new IllegalStateException(
          String.format(
              "Dependency-file rule key builders cannot process build rules. " +
                  "Was given %s to add to rule key.",
              rule));
    }

    private Iterable<SourcePath> getIterableInputsSoFar() {
      return Iterables.concat(inputs.build());
    }

    final Result buildResult() {
      return new Result(buildRuleKey(), Iterables.concat(inputs.build()));
    }

    @Override
    public RuleKey build() {
      return buildRuleKey();
    }
  }

  private static class Result {

    private final RuleKey ruleKey;
    private final Iterable<SourcePath> inputs;

    public Result(
        RuleKey ruleKey,
        Iterable<SourcePath> inputs) {
      this.ruleKey = ruleKey;
      this.inputs = inputs;
    }

    public RuleKey getRuleKey() {
      return ruleKey;
    }

    public Iterable<SourcePath> getInputs() {
      return inputs;
    }
  }

  @Override
  public Pair<RuleKey, ImmutableSet<SourcePath>> build(
      SupportsDependencyFileRuleKey rule,
      ImmutableList<DependencyFileEntry> depFileEntries) throws IOException {
    // Create a builder which records all `SourcePath`s which are possibly used by the rule.
    Builder builder = newInstance(rule);

    // TODO(jkeljo): Make this use possibleDepFileSourcePaths all the time instead of being Optional
    Optional<ImmutableSet<SourcePath>> possiblePaths =
        makeHashSet(rule.getPossibleInputSourcePaths());
    Predicate<SourcePath> interestingPathPredicate = rule.getExistenceOfInterestPredicate();

    ImmutableSet<DependencyFileEntry> depFileEntriesSet = ImmutableSet.copyOf(depFileEntries);

    final ImmutableSet.Builder<SourcePath> depFileSourcePathsBuilder = ImmutableSet.builder();
    final ImmutableSet.Builder<DependencyFileEntry> filesAccountedFor = ImmutableSet.builder();

    // Each existing input path falls into one of four categories:
    // 1) It's not covered by dep files, so we need to consider it part of the rule key.
    // 2) It's covered by dep files and present in the dep file, so we consider it part of the key.
    // 3) It's covered by dep files but not present in the dep file, however the existence is of
    //    interest, so we need to consider its path as part of the rule key.
    // 4) It's covered by dep files but not present in the dep file nor is existence of interest,
    //    so we don't include it in the rule key (the benefit of dep file support is based on the
    //    premise that lots of things fall in this category, so we can avoid rebuilds that would
    //    have happened with input-based rule keys)
    Iterable<SourcePath> inputsSoFar = builder.getIterableInputsSoFar();
    for (SourcePath input : inputsSoFar) {
      if (possiblePaths.isPresent() && !possiblePaths.get().contains(input)) {
        // If this path is not a dep file path, then add it to the builder directly.
        // Note that if {@code possibleDepFileSourcePaths} is not present, we treat
        // all the input files as covered by dep file, so we'll never end up here!
        builder.setSourcePathDirectly(input);
      } else {
        // If this input path is covered by the dep paths, check to see if it was declared
        // as a real dependency by the dep file entries
        DependencyFileEntry entry = DependencyFileEntry.fromSourcePath(input, pathResolver);
        if (depFileEntriesSet.contains(entry)) {
          builder.setSourcePathDirectly(input);
          depFileSourcePathsBuilder.add(input);
          filesAccountedFor.add(entry);
        } else if (interestingPathPredicate.test(input)) {
          builder.setNonHashingSourcePath(input);
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
    return new Pair<>(builder.build(), depFileSourcePathsBuilder.build());
  }

  @Override
  public Pair<RuleKey, ImmutableSet<SourcePath>> buildManifestKey(
      SupportsDependencyFileRuleKey rule)
      throws IOException {
    Builder builder = newInstance(rule);
    builder.setReflectively("buck.key_type", "manifest");

    Optional<ImmutableSet<SourcePath>> possiblePaths =
        makeHashSet(rule.getPossibleInputSourcePaths());
    Predicate<SourcePath> interestingPathPredicate = rule.getExistenceOfInterestPredicate();

    ImmutableSet<SourcePath> inputs = ImmutableSet.copyOf(builder.getIterableInputsSoFar());
    ImmutableSet.Builder<SourcePath> depFileInputs = ImmutableSet.builder();
    for (SourcePath input : inputs) {
      if (possiblePaths.isPresent() && !possiblePaths.get().contains(input)) {
        builder.setSourcePathDirectly(input);
      } else {
        depFileInputs.add(input);
        if (interestingPathPredicate.test(input)) {
          builder.setNonHashingSourcePath(input);
        }
      }
    }
    return new Pair<>(builder.build(), depFileInputs.build());
  }

  /**
   * Converts a sorted set to a hash set which allows constant time look-ups (instead of log n).
   */
  private static <T> Optional<ImmutableSet<T>> makeHashSet(Optional<ImmutableSet<T>> set) {
    return set.map(s -> ImmutableSet.copyOf(s));
  }

}
