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
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.cache.FileHashCache;
import com.google.common.base.Preconditions;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableSet;

import javax.annotation.Nonnull;

/**
 * A factory for generating input-based {@link RuleKey}s for {@link BuildRule}s which enumerate
 * their dependencies implicitly through their inputs, which are described by {@link SourcePath}s
 * added to their {@link RuleKey}.
 *
 * Input-based rule keys are generally more accurate than normal rule keys, as they won't
 * necessarily change if the rule key of a dependency changed.  Instead, they only change if a
 * the actual inputs to the rule change.
 */
public class InputBasedRuleKeyBuilderFactory extends DefaultRuleKeyBuilderFactory {

  private final InputHandling inputHandling;
  private final LoadingCache<RuleKeyAppendable, Pair<RuleKey, ImmutableSet<BuildRule>>> cache;

  protected InputBasedRuleKeyBuilderFactory(
      final FileHashCache hashCache,
      final SourcePathResolver pathResolver,
      InputHandling inputHandling) {
    super(hashCache, pathResolver);
    this.inputHandling = inputHandling;

    // Build the cache around the sub-rule-keys and their dep lists.
    cache = CacheBuilder.newBuilder().weakKeys().build(
        new CacheLoader<RuleKeyAppendable, Pair<RuleKey, ImmutableSet<BuildRule>>>() {
          @Override
          public Pair<RuleKey, ImmutableSet<BuildRule>> load(
              @Nonnull RuleKeyAppendable appendable) {
            Builder subKeyBuilder = new Builder(pathResolver, hashCache);
            appendable.appendToRuleKey(subKeyBuilder);
            return subKeyBuilder.buildWithDeps();
          }
        });
  }

  public InputBasedRuleKeyBuilderFactory(
      FileHashCache hashCache,
      SourcePathResolver pathResolver) {
    this(hashCache, pathResolver, InputHandling.HASH);
  }

  @Override
  protected RuleKey.Builder newBuilder(
      SourcePathResolver pathResolver,
      FileHashCache hashCache,
      final BuildRule rule) {
    return new Builder(pathResolver, hashCache) {

      // Construct the rule key, verifying that all the deps we saw when constructing it
      // are explicit dependencies of the rule.
      @Override
      public RuleKey build() {
        Pair<RuleKey, ImmutableSet<BuildRule>> result = buildWithDeps();
        Preconditions.checkState(rule.getDeps().containsAll(result.getSecond()));
        return result.getFirst();
      }

    };
  }

  public class Builder extends RuleKey.Builder {

    private final SourcePathResolver pathResolver;

    private final ImmutableSet.Builder<BuildRule> deps = ImmutableSet.builder();

    private Builder(
        SourcePathResolver pathResolver,
        FileHashCache hashCache) {
      super(pathResolver, hashCache);
      this.pathResolver = pathResolver;
    }

    @Override
    protected RuleKey getAppendableRuleKey(
        SourcePathResolver resolver,
        FileHashCache hashCache,
        RuleKeyAppendable appendable) {
      Pair<RuleKey, ImmutableSet<BuildRule>> result = cache.getUnchecked(appendable);
      deps.addAll(result.getSecond());
      return result.getFirst();
    }

    // Input-based rule keys are evaluated after all dependencies for a rule are available on
    // disk, and so we can always resolve the `Path` packaged in a `SourcePath`.  We hash this,
    // rather than the rule key from it's `BuildRule`.
    @Override
    protected RuleKey.Builder setSourcePath(SourcePath sourcePath) {
      if (inputHandling == InputHandling.HASH) {
        deps.addAll(pathResolver.getRule(sourcePath).asSet());
        setSingleValue(pathResolver.getPath(sourcePath));
      }
      return this;
    }

    // Rules supporting input-based rule keys should be described entirely by their `SourcePath`
    // inputs.  If we see a `BuildRule` when generating the rule key, this is likely a break in
    // that contract, so check for that.
    @Override
    protected RuleKey.Builder setBuildRule(BuildRule rule) {
      throw new IllegalStateException("Input-based rule key builders cannot process build rules");
    }

    // Build the rule key and the list of deps found from this builder.
    protected Pair<RuleKey, ImmutableSet<BuildRule>> buildWithDeps() {
      return new Pair<>(super.build(), deps.build());
    }

  }

  /**
   * How to handle adding {@link SourcePath}s to the {@link RuleKey}.
   */
  protected enum InputHandling {

    /**
     * Hash the contents of {@link SourcePath}s.
     */
    HASH,

    /**
     * Ignore {@link SourcePath}s.  This is useful for implementing handling for dependency files,
     * where the list of inputs will be provided explicitly.
     */
    IGNORE,

  }

}
