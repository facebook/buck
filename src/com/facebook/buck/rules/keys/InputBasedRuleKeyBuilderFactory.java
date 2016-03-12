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
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.RuleKeyBuilderFactory;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.cache.FileHashCache;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import java.io.IOException;
import java.util.Collections;

import javax.annotation.Nonnull;

/**
 * A factory for generating input-based {@link RuleKey}s.
 *
 * @see SupportsInputBasedRuleKey
 */
public class InputBasedRuleKeyBuilderFactory
    extends ReflectiveRuleKeyBuilderFactory<InputBasedRuleKeyBuilderFactory.Builder> {

  private final FileHashCache fileHashCache;
  private final SourcePathResolver pathResolver;
  private final InputHandling inputHandling;
  private final RuleKeyBuilderFactory defaultRuleKeyBuilderFactory;
  private final LoadingCache<RuleKeyAppendable, Result> cache;

  protected InputBasedRuleKeyBuilderFactory(
      FileHashCache hashCache,
      SourcePathResolver pathResolver,
      RuleKeyBuilderFactory defaultRuleKeyBuilderFactory,
      InputHandling inputHandling) {
    this.fileHashCache = hashCache;
    this.pathResolver = pathResolver;
    this.defaultRuleKeyBuilderFactory = defaultRuleKeyBuilderFactory;
    this.inputHandling = inputHandling;

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

  public InputBasedRuleKeyBuilderFactory(
      FileHashCache hashCache,
      SourcePathResolver pathResolver,
      RuleKeyBuilderFactory defaultRuleKeyBuilderFactory) {
    this(hashCache, pathResolver, defaultRuleKeyBuilderFactory, InputHandling.HASH);
  }

  @Override
  protected Builder newBuilder(final BuildRule rule) {
    return new Builder() {

      // Construct the rule key, verifying that all the deps we saw when constructing it
      // are explicit dependencies of the rule.
      @Override
      public RuleKey build() {
        Result result = buildResult();
        for (BuildRule usedDep : result.getDeps()) {
          Preconditions.checkState(
              rule.getDeps().contains(usedDep),
              "%s: %s not in deps (%s)",
              rule.getBuildTarget(),
              usedDep.getBuildTarget(),
              rule.getDeps());
        }
        return result.getRuleKey();
      }

    };
  }

  public class Builder extends RuleKeyBuilder {

    private final ImmutableList.Builder<Iterable<BuildRule>> deps = ImmutableList.builder();
    private final ImmutableList.Builder<Iterable<SourcePath>> inputs = ImmutableList.builder();

    private Builder() {
      super(pathResolver, fileHashCache, defaultRuleKeyBuilderFactory);
    }

    @Override
    protected RuleKey getAppendableRuleKey(
        SourcePathResolver resolver,
        FileHashCache hashCache,
        RuleKeyAppendable appendable) {
      Result result = cache.getUnchecked(appendable);
      deps.add(result.getDeps());
      inputs.add(result.getInputs());
      return result.getRuleKey();
    }

    // Input-based rule keys are evaluated after all dependencies for a rule are available on
    // disk, and so we can always resolve the `Path` packaged in a `SourcePath`.  We hash this,
    // rather than the rule key from it's `BuildRule`.
    @Override
    protected RuleKeyBuilder setSourcePath(SourcePath sourcePath) {
      if (inputHandling == InputHandling.HASH) {
        deps.add(pathResolver.getRule(sourcePath).asSet());

        try {
          setPath(
              pathResolver.getAbsolutePath(sourcePath),
              pathResolver.getRelativePath(sourcePath));
        } catch (IOException e) {
          throw Throwables.propagate(e);
        }
      }
      inputs.add(Collections.singleton(sourcePath));
      return this;
    }

    // Rules supporting input-based rule keys should be described entirely by their `SourcePath`
    // inputs.  If we see a `BuildRule` when generating the rule key, this is likely a break in
    // that contract, so check for that.
    @Override
    protected RuleKeyBuilder setBuildRule(BuildRule rule) {
      throw new IllegalStateException(
          String.format(
              "Input-based rule key builders cannot process build rules. " +
                  "Was given %s to add to rule key.",
              rule));
    }

    protected ImmutableSet<SourcePath> getInputsSoFar() {
      return ImmutableSet.copyOf(Iterables.concat(inputs.build()));
    }

    // Build the rule key and the list of deps found from this builder.
    protected Result buildResult() {
      return new Result(
          super.build(),
          Iterables.concat(deps.build()),
          Iterables.concat(inputs.build()));
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

  protected static class Result {

    private final RuleKey ruleKey;
    private final Iterable<BuildRule> deps;
    private final Iterable<SourcePath> inputs;

    public Result(
        RuleKey ruleKey,
        Iterable<BuildRule> deps,
        Iterable<SourcePath> inputs) {
      this.ruleKey = ruleKey;
      this.deps = deps;
      this.inputs = inputs;
    }

    public RuleKey getRuleKey() {
      return ruleKey;
    }

    public Iterable<BuildRule> getDeps() {
      return deps;
    }

    public Iterable<SourcePath> getInputs() {
      return inputs;
    }

  }

}
