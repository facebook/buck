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
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A factory for generating dependency-file {@link RuleKey}s.
 */
public abstract class AbstractDepFileRuleKeyFactory
    extends ReflectiveRuleKeyFactory<
            AbstractDepFileRuleKeyFactory.Builder,
            RuleKey> {

  private final FileHashLoader fileHashLoader;
  private final SourcePathResolver pathResolver;
  private final SourcePathRuleFinder ruleFinder;
  private final LoadingCache<RuleKeyAppendable, Result> cache;
  private final long inputSizeLimit;

  protected AbstractDepFileRuleKeyFactory(
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

  @Override
  protected Builder newBuilder(final BuildRule rule) {
    return new Builder();
  }

  public class Builder extends RuleKeyBuilder<RuleKey> {

    private final ImmutableList.Builder<Iterable<SourcePath>> inputs = ImmutableList.builder();
    private final SizeLimiter sizeLimiter = new SizeLimiter(inputSizeLimit);

    private Builder() {
      super(ruleFinder, pathResolver, fileHashLoader);
    }

    @Override
    public Builder setAppendableRuleKey(String key, RuleKeyAppendable appendable) {
      Result result = cache.getUnchecked(appendable);
      inputs.add(result.getInputs());
      setAppendableRuleKey(key, result.getRuleKey());
      return this;
    }

    @Override
    public Builder setReflectively(String key, @Nullable Object val) {
      if (val instanceof ArchiveDependencySupplier) {
        Object members = ((ArchiveDependencySupplier) val).getArchiveMembers(pathResolver);
        super.setReflectively(key, members);
      } else {
        super.setReflectively(key, val);
      }
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

    protected ImmutableSet<SourcePath> getInputsSoFar() {
      return ImmutableSet.copyOf(Iterables.concat(inputs.build()));
    }

    protected Iterable<SourcePath> getIterableInputsSoFar() {
      return Iterables.concat(inputs.build());
    }

    protected Result buildResult() {
      return new Result(buildRuleKey(), Iterables.concat(inputs.build()));
    }

    @Override
    public RuleKey build() {
      return buildRuleKey();
    }
  }

  protected static class Result {

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

}
