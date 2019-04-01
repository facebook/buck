/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.cxx.toolchain;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;

/** Used to provide a {@link CxxPlatform} that is specified as a cxx_toolchain build target. */
public class ProviderBasedUnresolvedCxxPlatform implements UnresolvedCxxPlatform {
  private final BuildTarget buildTarget;
  private final Flavor flavor;

  public ProviderBasedUnresolvedCxxPlatform(BuildTarget buildTarget, Flavor flavor) {
    this.buildTarget = buildTarget;
    this.flavor = flavor;
  }

  @Override
  public CxxPlatform resolve(BuildRuleResolver resolver) {
    BuildRule rule = resolver.getRule(buildTarget);
    Verify.verify(
        rule instanceof ProvidesCxxPlatform, "%s isn't a cxx_platform rule", rule.getBuildTarget());
    return ((ProvidesCxxPlatform) rule).getPlatformWithFlavor(flavor);
  }

  @Override
  public Flavor getFlavor() {
    return flavor;
  }

  @Override
  public UnresolvedCxxPlatform withFlavor(Flavor hostFlavor) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Iterable<BuildTarget> getParseTimeDeps(TargetConfiguration targetConfiguration) {
    return ImmutableList.of(buildTarget);
  }

  @Override
  public Iterable<? extends BuildTarget> getLinkerParseTimeDeps(
      TargetConfiguration targetConfiguration) {
    return ImmutableList.of(buildTarget);
  }
}
