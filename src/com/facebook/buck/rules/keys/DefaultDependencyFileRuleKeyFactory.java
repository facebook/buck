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

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.thrift.ThriftRuleKeyLogger;
import com.facebook.buck.rules.AddsToRuleKey;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.keys.hasher.RuleKeyHasher;
import com.facebook.buck.util.Scope;
import com.facebook.buck.util.hashing.FileHashLoader;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/** A factory for generating dependency-file {@link RuleKey}s. */
public final class DefaultDependencyFileRuleKeyFactory implements DependencyFileRuleKeyFactory {

  private final RuleKeyFieldLoader ruleKeyFieldLoader;
  private final FileHashLoader fileHashLoader;
  private final SourcePathResolver pathResolver;
  private final SourcePathRuleFinder ruleFinder;
  private final long inputSizeLimit;
  private final Optional<ThriftRuleKeyLogger> ruleKeyLogger;

  private DefaultDependencyFileRuleKeyFactory(
      RuleKeyFieldLoader ruleKeyFieldLoader,
      FileHashLoader hashLoader,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder,
      long inputSizeLimit,
      Optional<ThriftRuleKeyLogger> ruleKeyLogger) {
    this.ruleKeyFieldLoader = ruleKeyFieldLoader;
    this.fileHashLoader = hashLoader;
    this.pathResolver = pathResolver;
    this.ruleFinder = ruleFinder;
    this.inputSizeLimit = inputSizeLimit;
    this.ruleKeyLogger = ruleKeyLogger;
  }

  public DefaultDependencyFileRuleKeyFactory(
      RuleKeyFieldLoader ruleKeyFieldLoader,
      FileHashLoader hashLoader,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder,
      Optional<ThriftRuleKeyLogger> ruleKeyLogger) {
    this(ruleKeyFieldLoader, hashLoader, pathResolver, ruleFinder, Long.MAX_VALUE, ruleKeyLogger);
  }

  public DefaultDependencyFileRuleKeyFactory(
      RuleKeyFieldLoader ruleKeyFieldLoader,
      FileHashLoader hashLoader,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder) {
    this(
        ruleKeyFieldLoader, hashLoader, pathResolver, ruleFinder, Long.MAX_VALUE, Optional.empty());
  }

  @Override
  public RuleKeyAndInputs build(
      SupportsDependencyFileRuleKey rule, ImmutableList<DependencyFileEntry> depFileEntries)
      throws IOException {
    // Note, we do not cache this as it didn't show performance improvements.
    return buildKey(rule, KeyType.DEP_FILE, depFileEntries);
  }

  @Override
  public RuleKeyAndInputs buildManifestKey(SupportsDependencyFileRuleKey rule) throws IOException {
    // Note, we do not cache this as it didn't show performance improvements.
    return buildKey(rule, KeyType.MANIFEST, ImmutableList.of());
  }

  private RuleKeyAndInputs buildKey(
      SupportsDependencyFileRuleKey rule,
      KeyType keyType,
      ImmutableList<DependencyFileEntry> depFileEntries)
      throws IOException {
    Builder<HashCode> builder =
        new Builder<>(
            rule,
            keyType,
            depFileEntries,
            rule.getCoveredByDepFilePredicate(pathResolver),
            rule.getExistenceOfInterestPredicate(pathResolver),
            RuleKeyBuilder.createDefaultHasher(ruleKeyLogger));
    ruleKeyFieldLoader.setFields(builder, rule, keyType.toRuleKeyType());
    Result<RuleKey> result = builder.buildResult(RuleKey::new);
    return RuleKeyAndInputs.of(result.getRuleKey(), result.getSourcePaths());
  }

  private class Builder<RULE_KEY> extends RuleKeyBuilder<RULE_KEY> {

    private final SupportsDependencyFileRuleKey rule;
    private final KeyType keyType;
    private final ImmutableSet<DependencyFileEntry> depFileEntriesSet;

    private final Predicate<SourcePath> coveredPathPredicate;
    private final Predicate<SourcePath> interestingPathPredicate;

    final ImmutableSet.Builder<SourcePath> sourcePaths = ImmutableSet.builder();
    final ImmutableSet.Builder<DependencyFileEntry> accountedEntries = ImmutableSet.builder();

    private final SizeLimiter sizeLimiter = new SizeLimiter(inputSizeLimit);

    private Builder(
        SupportsDependencyFileRuleKey rule,
        KeyType keyType,
        ImmutableList<DependencyFileEntry> depFileEntries,
        Predicate<SourcePath> coveredPathPredicate,
        Predicate<SourcePath> interestingPathPredicate,
        RuleKeyHasher<RULE_KEY> hasher) {
      super(ruleFinder, pathResolver, fileHashLoader, hasher);
      this.keyType = keyType;
      this.rule = rule;
      this.depFileEntriesSet = ImmutableSet.copyOf(depFileEntries);
      this.coveredPathPredicate = coveredPathPredicate;
      this.interestingPathPredicate = interestingPathPredicate;
    }

    @Override
    protected Builder<RULE_KEY> setAddsToRuleKey(AddsToRuleKey appendable) {
      // Note, we do not compute a separate `RuleKey` for `RuleKeyAppendables`. Instead we just hash
      // the content directly under the appendable scope. Collision-wise there is no difference. The
      // former allowed us to do caching, but it turns out that didn't make much of a difference
      // performance-wise. Furthermore, after fixing this factory to account for the field names and
      // structure while hashing `SourcePaths`, caching `RuleKeyAppendables` becomes much more
      // trickier. We can't perform hashing immediately because `SourcePaths` of the same appendable
      // instance may be handled differently when referenced by different build rules. Therefore we
      // need to defer that work to be done at the time a particular build rule is being handled.
      // Instead of keeping a simple set of `SourcePaths`, we'd also have to keep the structure
      // information for each path. In particular, each path found in the following field
      // `@AddToRuleKey Optional<ImmutableList<SourcePath>> myPaths` would have to be accompanied by
      // its structure information: `myPaths;Optional;List`. This adds additional overhead of
      // bookkeeping that information and counters any benefits caching would provide here.
      try (Scope ignored = getScopedHasher().wrapperScope(RuleKeyHasher.Wrapper.APPENDABLE)) {
        try (RuleKeyScopedHasher.ContainerScope tupleScope =
            getScopedHasher().containerScope(RuleKeyHasher.Container.TUPLE)) {
          AlterRuleKeys.amendKey(new ScopedRuleKeyObjectSink(tupleScope, this), appendable);
        }
      }
      return this;
    }

    @Override
    protected Builder<RULE_KEY> setReflectively(@Nullable Object val) throws IOException {
      if (val instanceof ArchiveDependencySupplier) {
        Iterable<SourcePath> members =
            ((ArchiveDependencySupplier) val).getArchiveMembers(pathResolver)::iterator;
        super.setReflectively(members);
      } else {
        super.setReflectively(val);
      }
      return this;
    }

    @Override
    public Builder<RULE_KEY> setPath(Path absolutePath, Path ideallyRelative) throws IOException {
      if (inputSizeLimit != Long.MAX_VALUE) {
        sizeLimiter.add(fileHashLoader.getSize(absolutePath));
      }
      super.setPath(absolutePath, ideallyRelative);
      return this;
    }

    @Override
    protected Builder<RULE_KEY> setPath(ProjectFilesystem filesystem, Path relativePath)
        throws IOException {
      if (inputSizeLimit != Long.MAX_VALUE) {
        sizeLimiter.add(fileHashLoader.getSize(filesystem, relativePath));
      }
      super.setPath(filesystem, relativePath);
      return this;
    }

    @Override
    protected Builder<RULE_KEY> setSourcePath(SourcePath input) throws IOException {
      if (keyType == KeyType.DEP_FILE) {
        // Each existing input path falls into one of four categories:
        // 1) It's not covered by dep-files, so we need to consider it part of the rule key.
        // 2) It's covered by dep-files and present in the dep-file, so we need to consider it part
        //    of the rule key.
        // 3) It's covered by dep-files but not present in the dep-file, however the existence is
        //    of interest, so we need to consider its path as part of the rule key.
        // 4) It's covered by dep-files but not present in the dep-file nor is existence of interest
        //    so we don't include it in the rule key. The benefit of dep-file support is based on
        //    the premise that lots of things fall in this category, so we can avoid rebuilds that
        //    would have happened with input-based rule keys.
        if (!coveredPathPredicate.test(input)) {
          // 1: If this path is not covered by dep-file, then add it to the builder directly.
          this.setSourcePathDirectly(input);
        } else {
          // 2,3,4: This input path is covered by the dep-file
          DependencyFileEntry entry = DependencyFileEntry.fromSourcePath(input, pathResolver);
          if (depFileEntriesSet.contains(entry)) {
            // 2: input was declared as a real dependency by the dep-file entries so add to key
            this.setSourcePathDirectly(input);
            sourcePaths.add(input);
            accountedEntries.add(entry);
          } else if (interestingPathPredicate.test(input)) {
            // 3: path not present in the dep-file, however the existence is of interest
            this.setNonHashingSourcePath(input);
          }
        }
      } else {
        // Comparing to dep-file keys, manifest keys gets constructed as if no covered input is
        // used, but we return the list of all such covered inputs for further inspection.
        if (!coveredPathPredicate.test(input)) {
          this.setSourcePathDirectly(input);
        } else {
          sourcePaths.add(input);
          if (interestingPathPredicate.test(input)) {
            this.setNonHashingSourcePath(input);
          }
        }
      }
      return this;
    }

    @Override
    protected Builder<RULE_KEY> setNonHashingSourcePath(SourcePath sourcePath) {
      setNonHashingSourcePathDirectly(sourcePath);
      return this;
    }

    // Rules supporting dep-file rule keys should be described entirely by their `SourcePath`
    // inputs.  If we see a `BuildRule` when generating the rule key, this is likely a break in
    // that contract, so check for that.
    @Override
    protected Builder<RULE_KEY> setBuildRule(BuildRule rule) {
      throw new IllegalStateException(
          String.format(
              "Dependency-file rule key builders cannot process build rules. "
                  + "Was given %s to add to rule key.",
              rule));
    }

    final <RESULT> Result<RESULT> buildResult(Function<RULE_KEY, RESULT> mapper)
        throws IOException {
      if (keyType == KeyType.DEP_FILE) {
        // If we don't find actual inputs in one of the rules that corresponded to the input, this
        // likely means that the rule changed to no longer use the input. In this case we need to
        // throw a `NoSuchFileException` so that the build engine handles this as a signal that the
        // dep file rule key cannot be used.
        Sets.SetView<DependencyFileEntry> unaccountedEntries =
            Sets.difference(depFileEntriesSet, accountedEntries.build());
        if (!unaccountedEntries.isEmpty()) {
          throw new NoSuchFileException(
              String.format(
                  "%s: could not find any inputs matching the relative paths [%s]",
                  rule.getBuildTarget(), Joiner.on(',').join(unaccountedEntries)));
        }
      }
      return new Result<>(this.build(mapper), sourcePaths.build());
    }
  }

  private static class Result<RULE_KEY> {

    private final RULE_KEY ruleKey;
    private final ImmutableSet<SourcePath> sourcePaths;

    public Result(RULE_KEY ruleKey, ImmutableSet<SourcePath> sourcePaths) {
      this.ruleKey = ruleKey;
      this.sourcePaths = sourcePaths;
    }

    public RULE_KEY getRuleKey() {
      return ruleKey;
    }

    public ImmutableSet<SourcePath> getSourcePaths() {
      return sourcePaths;
    }
  }

  private enum KeyType {
    DEP_FILE(RuleKeyType.DEP_FILE),
    MANIFEST(RuleKeyType.MANIFEST),
    ;

    private final RuleKeyType ruleKeyType;

    KeyType(RuleKeyType ruleKeyType) {
      this.ruleKeyType = ruleKeyType;
    }

    public RuleKeyType toRuleKeyType() {
      return ruleKeyType;
    }
  }
}
