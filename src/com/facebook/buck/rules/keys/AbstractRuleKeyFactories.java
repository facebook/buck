/*
 * Copyright 2017-present Facebook, Inc.
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

import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.RuleKeyCalculationEvent;
import com.facebook.buck.log.thrift.ThriftRuleKeyLogger;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.keys.config.RuleKeyConfiguration;
import com.facebook.buck.util.Scope;
import com.facebook.buck.util.cache.FileHashCache;
import java.io.IOException;
import java.util.Optional;
import org.immutables.value.Value;

/** The various rule key factories used by the build engine. */
@Value.Immutable
@BuckStyleTuple
abstract class AbstractRuleKeyFactories {

  /** @return a {@link RuleKeyFactory} that produces {@link RuleKey}s. */
  public abstract RuleKeyFactoryWithDiagnostics<RuleKey> getDefaultRuleKeyFactory();

  /** @return a {@link RuleKeyFactory} that produces input-based {@link RuleKey}s. */
  public abstract RuleKeyFactory<RuleKey> getInputBasedRuleKeyFactory();

  /**
   * @return a {@link DependencyFileRuleKeyFactory} that produces dep-file-based {@link RuleKey}s.
   */
  public abstract DependencyFileRuleKeyFactory getDepFileRuleKeyFactory();

  public static RuleKeyFactories of(
      RuleKeyConfiguration ruleKeyConfiguration,
      FileHashCache fileHashCache,
      BuildRuleResolver resolver,
      long inputRuleKeyFileSizeLimit,
      TrackedRuleKeyCache<RuleKey> defaultRuleKeyFactoryCache) {
    return of(
        ruleKeyConfiguration,
        fileHashCache,
        resolver,
        inputRuleKeyFileSizeLimit,
        defaultRuleKeyFactoryCache,
        Optional.empty());
  }

  public static RuleKeyFactories of(
      RuleKeyConfiguration ruleKeyConfiguration,
      FileHashCache fileHashCache,
      BuildRuleResolver resolver,
      long inputRuleKeyFileSizeLimit,
      TrackedRuleKeyCache<RuleKey> defaultRuleKeyFactoryCache,
      Optional<ThriftRuleKeyLogger> ruleKeyLogger) {
    RuleKeyFieldLoader fieldLoader = new RuleKeyFieldLoader(ruleKeyConfiguration);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    return RuleKeyFactories.of(
        new DefaultRuleKeyFactory(
            fieldLoader,
            fileHashCache,
            pathResolver,
            ruleFinder,
            defaultRuleKeyFactoryCache,
            ruleKeyLogger),
        new InputBasedRuleKeyFactory(
            fieldLoader,
            fileHashCache,
            pathResolver,
            ruleFinder,
            inputRuleKeyFileSizeLimit,
            ruleKeyLogger),
        new DefaultDependencyFileRuleKeyFactory(
            fieldLoader, fileHashCache, pathResolver, ruleFinder, ruleKeyLogger));
  }

  public Optional<RuleKeyAndInputs> calculateManifestKey(
      SupportsDependencyFileRuleKey rule, BuckEventBus eventBus) throws IOException {
    try (Scope scope =
        RuleKeyCalculationEvent.scope(
            eventBus, RuleKeyCalculationEvent.Type.MANIFEST, rule.getBuildTarget())) {
      return Optional.of(getDepFileRuleKeyFactory().buildManifestKey(rule));
    } catch (SizeLimiter.SizeLimitException ex) {
      return Optional.empty();
    }
  }
}
