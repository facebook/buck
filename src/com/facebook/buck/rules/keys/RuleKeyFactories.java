/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.rules.keys;

import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.attr.SupportsDependencyFileRuleKey;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.RuleKeyCalculationEvent;
import com.facebook.buck.log.thrift.ThriftRuleKeyLogger;
import com.facebook.buck.rules.keys.config.RuleKeyConfiguration;
import com.facebook.buck.util.Scope;
import com.facebook.buck.util.hashing.FileHashLoader;
import java.io.IOException;
import java.util.Optional;

/** The various rule key factories used by the build engine. */
@BuckStyleValue
public abstract class RuleKeyFactories {

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
      FileHashLoader fileHashLoader,
      BuildRuleResolver resolver,
      long inputRuleKeyFileSizeLimit,
      TrackedRuleKeyCache<RuleKey> defaultRuleKeyFactoryCache) {
    return of(
        ruleKeyConfiguration,
        fileHashLoader,
        resolver,
        inputRuleKeyFileSizeLimit,
        defaultRuleKeyFactoryCache,
        Optional.empty());
  }

  public static RuleKeyFactories of(
      RuleKeyConfiguration ruleKeyConfiguration,
      FileHashLoader fileHashLoader,
      BuildRuleResolver resolver,
      long inputRuleKeyFileSizeLimit,
      TrackedRuleKeyCache<RuleKey> defaultRuleKeyFactoryCache,
      Optional<ThriftRuleKeyLogger> ruleKeyLogger) {
    RuleKeyFieldLoader fieldLoader = new RuleKeyFieldLoader(ruleKeyConfiguration);
    return of(
        new DefaultRuleKeyFactory(
            fieldLoader, fileHashLoader, resolver, defaultRuleKeyFactoryCache, ruleKeyLogger),
        new InputBasedRuleKeyFactory(
            fieldLoader, fileHashLoader, resolver, inputRuleKeyFileSizeLimit, ruleKeyLogger),
        new DefaultDependencyFileRuleKeyFactory(
            fieldLoader, fileHashLoader, resolver, ruleKeyLogger));
  }

  public static RuleKeyFactories of(
      RuleKeyFactoryWithDiagnostics<RuleKey> defaultRuleKeyFactory,
      RuleKeyFactory<RuleKey> inputBasedRuleKeyFactory,
      DependencyFileRuleKeyFactory depFileRuleKeyFactory) {
    return ImmutableRuleKeyFactories.of(
        defaultRuleKeyFactory, inputBasedRuleKeyFactory, depFileRuleKeyFactory);
  }

  public Optional<DependencyFileRuleKeyFactory.RuleKeyAndInputs> calculateManifestKey(
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
