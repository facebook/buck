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
import com.facebook.buck.android.toolchain.ndk.UnresolvedNdkCxxPlatform;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.cxx.toolchain.StaticUnresolvedCxxPlatform;
import com.facebook.buck.cxx.toolchain.UnresolvedCxxPlatform;
import org.immutables.value.Value;

/**
 * Used to provide a {@link NdkCxxPlatform} that is fully specified before parsing/configuration
 * (specified in .buckconfig, for example).
 */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractStaticUnresolvedNdkCxxPlatform implements UnresolvedNdkCxxPlatform {
  @Value.Parameter
  public abstract NdkCxxPlatform getStaticallyResolvedInstance();

  @Override
  public Iterable<BuildTarget> getParseTimeDeps(TargetConfiguration targetConfiguration) {
    return getCxxPlatform().getParseTimeDeps(targetConfiguration);
  }

  @Override
  public NdkCxxPlatform resolve(BuildRuleResolver ruleResolver) {
    return getStaticallyResolvedInstance();
  }

  @Value.Derived
  @Override
  public UnresolvedCxxPlatform getCxxPlatform() {
    return new StaticUnresolvedCxxPlatform(getStaticallyResolvedInstance().getCxxPlatform());
  }
}
