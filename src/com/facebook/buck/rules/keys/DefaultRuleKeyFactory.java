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

import com.facebook.buck.log.thrift.ThriftRuleKeyLogger;
import com.facebook.buck.rules.AddsToRuleKey;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.HasDeclaredAndExtraDeps;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.keys.hasher.RuleKeyHasher;
import com.facebook.buck.util.cache.NoOpCacheStatsTracker;
import com.facebook.buck.util.hashing.FileHashLoader;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.util.Optional;
import java.util.function.Function;
import javax.annotation.Nullable;

/** A {@link RuleKeyFactory} which adds some default settings to {@link RuleKey}s. */
public class DefaultRuleKeyFactory implements RuleKeyFactoryWithDiagnostics<RuleKey> {

  private final RuleKeyFieldLoader ruleKeyFieldLoader;
  private final FileHashLoader hashLoader;
  private final SourcePathResolver pathResolver;
  private final SourcePathRuleFinder ruleFinder;
  private final RuleKeyCache<RuleKey> ruleKeyCache;
  private final Optional<ThriftRuleKeyLogger> ruleKeyLogger;

  public DefaultRuleKeyFactory(
      RuleKeyFieldLoader ruleKeyFieldLoader,
      FileHashLoader hashLoader,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder,
      RuleKeyCache<RuleKey> ruleKeyCache,
      Optional<ThriftRuleKeyLogger> ruleKeyLogger) {
    this.ruleKeyFieldLoader = ruleKeyFieldLoader;
    this.hashLoader = hashLoader;
    this.pathResolver = pathResolver;
    this.ruleFinder = ruleFinder;
    this.ruleKeyCache = ruleKeyCache;
    this.ruleKeyLogger = ruleKeyLogger;
  }

  public DefaultRuleKeyFactory(
      RuleKeyFieldLoader ruleKeyFieldLoader,
      FileHashLoader hashLoader,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder) {
    this(
        ruleKeyFieldLoader,
        hashLoader,
        pathResolver,
        ruleFinder,
        new TrackedRuleKeyCache<>(new DefaultRuleKeyCache<>(), new NoOpCacheStatsTracker()),
        Optional.empty());
  }

  public DefaultRuleKeyFactory(
      RuleKeyFieldLoader ruleKeyFieldLoader,
      FileHashLoader hashLoader,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder,
      Optional<ThriftRuleKeyLogger> ruleKeyLogger) {
    this(
        ruleKeyFieldLoader,
        hashLoader,
        pathResolver,
        ruleFinder,
        new TrackedRuleKeyCache<>(new DefaultRuleKeyCache<>(), new NoOpCacheStatsTracker()),
        ruleKeyLogger);
  }

  private <HASH> Builder<HASH> newPopulatedBuilder(
      BuildRule buildRule, RuleKeyHasher<HASH> hasher) {
    Builder<HASH> builder = new Builder<>(hasher);
    ruleKeyFieldLoader.setFields(builder, buildRule, RuleKeyType.DEFAULT);
    addDepsToRuleKey(buildRule, builder);
    return builder;
  }

  private <HASH> Builder<HASH> newPopulatedBuilder(
      AddsToRuleKey appendable, RuleKeyHasher<HASH> hasher) {
    Builder<HASH> builder = new Builder<>(hasher);
    AlterRuleKeys.amendKey(builder, appendable);
    return builder;
  }

  @VisibleForTesting
  public Builder<HashCode> newBuilderForTesting(BuildRule buildRule) {
    return newPopulatedBuilder(buildRule, RuleKeyBuilder.createDefaultHasher(ruleKeyLogger));
  }

  @Nullable
  @Override
  public RuleKey getFromCache(BuildRule buildRule) {
    return ruleKeyCache.get(buildRule);
  }

  @Override
  public RuleKey build(BuildRule buildRule) {
    return ruleKeyCache.get(
        buildRule,
        rule ->
            newPopulatedBuilder(rule, RuleKeyBuilder.createDefaultHasher(ruleKeyLogger))
                .buildResult(RuleKey::new));
  }

  private RuleKey buildAppendableKey(AddsToRuleKey appendable) {
    return ruleKeyCache.get(
        appendable,
        app ->
            newPopulatedBuilder(app, RuleKeyBuilder.createDefaultHasher(ruleKeyLogger))
                .buildResult(RuleKey::new));
  }

  @Override
  public <DIAG_KEY> RuleKeyDiagnostics.Result<RuleKey, DIAG_KEY> buildForDiagnostics(
      BuildRule buildRule, RuleKeyHasher<DIAG_KEY> hasher) {
    return RuleKeyDiagnostics.Result.of(
        build(buildRule), // real rule key
        newPopulatedBuilder(buildRule, hasher).buildResult(Function.identity()));
  }

  @Override
  public <DIAG_KEY> RuleKeyDiagnostics.Result<RuleKey, DIAG_KEY> buildForDiagnostics(
      AddsToRuleKey appendable, RuleKeyHasher<DIAG_KEY> hasher) {
    return RuleKeyDiagnostics.Result.of(
        buildAppendableKey(appendable), // real rule key
        newPopulatedBuilder(appendable, hasher).buildResult(Function.identity()));
  }

  private void addDepsToRuleKey(BuildRule buildRule, RuleKeyObjectSink sink) {
    if (buildRule instanceof HasDeclaredAndExtraDeps) {
      // TODO(mkosiba): We really need to get rid of declared/extra deps in rules. Instead
      // rules should explicitly take the needed sub-sets of deps as constructor args.
      HasDeclaredAndExtraDeps hasDeclaredAndExtraDeps = (HasDeclaredAndExtraDeps) buildRule;
      sink.setReflectively("buck.extraDeps", hasDeclaredAndExtraDeps.deprecatedGetExtraDeps());
      sink.setReflectively("buck.declaredDeps", hasDeclaredAndExtraDeps.getDeclaredDeps());
    } else {
      sink.setReflectively("buck.deps", buildRule.getBuildDeps());
    }
  }

  public class Builder<RULE_KEY> extends RuleKeyBuilder<RULE_KEY> {

    private final ImmutableList.Builder<Object> deps = ImmutableList.builder();
    private final ImmutableList.Builder<RuleKeyInput> inputs = ImmutableList.builder();

    public Builder(RuleKeyHasher<RULE_KEY> hasher) {
      super(ruleFinder, pathResolver, hashLoader, hasher);
    }

    @Override
    protected RuleKeyBuilder<RULE_KEY> setBuildRule(BuildRule rule) {
      // Record the `BuildRule` as an immediate dep.
      deps.add(rule);
      return setBuildRuleKey(DefaultRuleKeyFactory.this.build(rule));
    }

    @Override
    protected RuleKeyBuilder<RULE_KEY> setAddsToRuleKey(AddsToRuleKey appendable) {
      // Record the `AddsToRuleKey` as an immediate dep.
      deps.add(appendable);
      return setAddsToRuleKey(DefaultRuleKeyFactory.this.buildAppendableKey(appendable));
    }

    @Override
    protected RuleKeyBuilder<RULE_KEY> setSourcePath(SourcePath sourcePath) throws IOException {
      if (sourcePath instanceof BuildTargetSourcePath) {
        return setSourcePathAsRule((BuildTargetSourcePath) sourcePath);
      } else {
        // Add `PathSourcePath`s to our tracked inputs.
        pathResolver
            .getPathSourcePath(sourcePath)
            .ifPresent(
                path -> inputs.add(RuleKeyInput.of(path.getFilesystem(), path.getRelativePath())));
        return setSourcePathDirectly(sourcePath);
      }
    }

    @Override
    protected RuleKeyBuilder<RULE_KEY> setNonHashingSourcePath(SourcePath sourcePath) {
      try {
        // The default rule keys must include the hash of the source path to properly propagate
        // changes to dependent rule keys.
        return setSourcePath(sourcePath);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    public <RESULT> RuleKeyResult<RESULT> buildResult(Function<RULE_KEY, RESULT> mapper) {
      return new RuleKeyResult<>(this.build(mapper), deps.build(), inputs.build());
    }
  }
}
