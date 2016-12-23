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
import com.facebook.buck.rules.ArchiveMemberSourcePath;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.DependencyAggregation;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.google.common.base.Preconditions;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import java.io.IOException;
import java.nio.file.Path;

import javax.annotation.Nonnull;

/**
 * A factory for generating input-based {@link RuleKey}s.
 *
 * @see SupportsInputBasedRuleKey
 */
public final class InputBasedRuleKeyFactory
    extends ReflectiveRuleKeyFactory<
            InputBasedRuleKeyFactory.Builder,
            RuleKey> {

  private final FileHashLoader fileHashLoader;
  private final SourcePathResolver pathResolver;
  private final SourcePathRuleFinder ruleFinder;
  private final LoadingCache<RuleKeyAppendable, Result> cache;
  private final long inputSizeLimit;

  public InputBasedRuleKeyFactory(
      int seed,
      FileHashLoader hashLoader,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder,
      long inputSizeLimit) {
    super(seed);
    this.fileHashLoader = hashLoader;
    this.pathResolver = pathResolver;
    this.ruleFinder = ruleFinder;
    this.inputSizeLimit = inputSizeLimit;

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

  public InputBasedRuleKeyFactory(
      int seed,
      FileHashLoader hashLoader,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder) {
    this(seed, hashLoader, pathResolver, ruleFinder, Long.MAX_VALUE);
  }

  @Override
  protected Builder newBuilder(final BuildRule rule) {
    final Iterable<DependencyAggregation> aggregatedRules =
        Iterables.filter(rule.getDeps(), DependencyAggregation.class);
    return new Builder() {
      private boolean hasEffectiveDirectDep(BuildRule dep) {
        for (BuildRule aggregationRule : aggregatedRules) {
          if (aggregationRule.getDeps().contains(dep)) {
            return true;
          }
        }
        return false;
      }

      // Construct the rule key, verifying that all the deps we saw when constructing it
      // are explicit dependencies of the rule.
      @Override
      public RuleKey build() {
        Result result = buildResult();
        for (BuildRule usedDep : result.getDeps()) {
          Preconditions.checkState(
              rule.getDeps().contains(usedDep) || hasEffectiveDirectDep(usedDep),
              "%s: %s not in deps (%s)",
              rule.getBuildTarget(),
              usedDep.getBuildTarget(),
              rule.getDeps());
        }
        return result.getRuleKey();
      }

    };
  }

  /* package */ class Builder extends RuleKeyBuilder<RuleKey> {

    private final ImmutableList.Builder<Iterable<BuildRule>> deps = ImmutableList.builder();
    private final SizeLimiter sizeLimiter = new SizeLimiter(inputSizeLimit);

    private Builder() {
      super(ruleFinder, pathResolver, fileHashLoader);
    }

    @Override
    public Builder setAppendableRuleKey(String key, RuleKeyAppendable appendable) {
      Result result = cache.getUnchecked(appendable);
      deps.add(result.getDeps());
      setAppendableRuleKey(key, result.getRuleKey());
      return this;
    }

    @Override
    public Builder setPath(Path absolutePath, Path ideallyRelative) throws IOException {
      // TODO(plamenko): this check should not be necessary, but otherwise some tests fail due to
      // FileHashLoader throwing NoSuchFileException which doesn't get correctly propagated.
      if (inputSizeLimit != Long.MAX_VALUE) {
        sizeLimiter.add(fileHashLoader.getSize(absolutePath));
      }
      super.setPath(absolutePath, ideallyRelative);
      return this;
    }

    // Input-based rule keys are evaluated after all dependencies for a rule are available on
    // disk, and so we can always resolve the `Path` packaged in a `SourcePath`.  We hash this,
    // rather than the rule key from it's `BuildRule`.
    @Override
    protected Builder setSourcePath(SourcePath sourcePath) {
      if (sourcePath instanceof BuildTargetSourcePath) {
        deps.add(ImmutableSet.of(ruleFinder.getRuleOrThrow((BuildTargetSourcePath) sourcePath)));
        // fall through and call setPath as well
      }
      try {
        if (sourcePath instanceof ArchiveMemberSourcePath) {
          setArchiveMemberPath(
              pathResolver.getAbsoluteArchiveMemberPath(sourcePath),
              pathResolver.getRelativeArchiveMemberPath(sourcePath));
        } else {
          setPath(
              pathResolver.getAbsolutePath(sourcePath),
              pathResolver.getRelativePath(sourcePath));
        }
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      return this;
    }

    // Rules supporting input-based rule keys should be described entirely by their `SourcePath`
    // inputs.  If we see a `BuildRule` when generating the rule key, this is likely a break in
    // that contract, so check for that.
    @Override
    protected Builder setBuildRule(BuildRule rule) {
      throw new IllegalStateException(
          String.format(
              "Input-based rule key builders cannot process build rules. " +
                  "Was given %s to add to rule key.",
              rule));
    }

    // Build the rule key and the list of deps found from this builder.
    final Result buildResult() {
      return new Result(buildRuleKey(), Iterables.concat(deps.build()));
    }

    @Override
    public RuleKey build() {
      return buildRuleKey();
    }
  }

  private static class Result {

    private final RuleKey ruleKey;
    private final Iterable<BuildRule> deps;

    public Result(
        RuleKey ruleKey,
        Iterable<BuildRule> deps) {
      this.ruleKey = ruleKey;
      this.deps = deps;
    }

    public RuleKey getRuleKey() {
      return ruleKey;
    }

    public Iterable<BuildRule> getDeps() {
      return deps;
    }
  }

}
