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
package com.facebook.buck.android.toolchain.ndk.impl;

import com.facebook.buck.android.toolchain.ndk.NdkCxxPlatform;
import com.facebook.buck.android.toolchain.ndk.ProvidesNdkCxxPlatform;
import com.facebook.buck.android.toolchain.ndk.UnresolvedNdkCxxPlatform;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.UnresolvedCxxPlatform;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;

/** Used to provide a {@link NdkCxxPlatform} that is specified as a ndk_toolchain build target. */
public class ProviderBackedUnresolvedNdkCxxPlatform implements UnresolvedNdkCxxPlatform {
  private final BuildTarget buildTarget;
  private final Flavor flavor;
  private final UnresolvedCxxPlatform cxxPlatformProvider;

  public ProviderBackedUnresolvedNdkCxxPlatform(BuildTarget buildTarget, Flavor flavor) {
    this.buildTarget = buildTarget;
    this.flavor = flavor;
    this.cxxPlatformProvider =
        new UnresolvedCxxPlatform() {
          @Override
          public CxxPlatform resolve(BuildRuleResolver resolver) {
            return ProviderBackedUnresolvedNdkCxxPlatform.this.resolve(resolver).getCxxPlatform();
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
            return ProviderBackedUnresolvedNdkCxxPlatform.this.getParseTimeDeps(
                targetConfiguration);
          }

          @Override
          public Iterable<? extends BuildTarget> getLinkerParseTimeDeps(
              TargetConfiguration targetConfiguration) {
            return getParseTimeDeps(targetConfiguration);
          }
        };
  }

  @Override
  public Iterable<BuildTarget> getParseTimeDeps(TargetConfiguration targetConfiguration) {
    return ImmutableList.of(buildTarget);
  }

  @Override
  public NdkCxxPlatform resolve(BuildRuleResolver ruleResolver) {
    BuildRule rule = ruleResolver.getRule(buildTarget);
    Verify.verify(rule instanceof ProvidesNdkCxxPlatform);
    NdkCxxPlatform platform = ((ProvidesNdkCxxPlatform) rule).getNdkCxxPlatform(flavor);
    return platform;
  }

  @Override
  public UnresolvedCxxPlatform getCxxPlatform() {
    return cxxPlatformProvider;
  }
}
