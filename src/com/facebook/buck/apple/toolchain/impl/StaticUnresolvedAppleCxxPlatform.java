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

package com.facebook.buck.apple.toolchain.impl;

import com.facebook.buck.apple.toolchain.AppleCxxPlatform;
import com.facebook.buck.apple.toolchain.UnresolvedAppleCxxPlatform;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.cxx.toolchain.UnresolvedCxxPlatform;
import com.facebook.buck.cxx.toolchain.impl.StaticUnresolvedCxxPlatform;
import com.facebook.buck.swift.toolchain.UnresolvedSwiftPlatform;
import com.facebook.buck.swift.toolchain.impl.StaticUnresolvedSwiftPlatform;
import com.google.common.collect.ImmutableList;

/**
 * Used to provide a {@link AppleCxxPlatform} that is fully specified before parsing/configuration
 * (specified in .buckconfig, for example).
 */
@BuckStyleValue
public abstract class StaticUnresolvedAppleCxxPlatform implements UnresolvedAppleCxxPlatform {
  public abstract AppleCxxPlatform getStaticallyResolvedInstance();

  @Override
  public abstract Flavor getFlavor();

  @Override
  public Iterable<BuildTarget> getParseTimeDeps(TargetConfiguration targetConfiguration) {
    ImmutableList.Builder<BuildTarget> deps = ImmutableList.builder();
    deps.addAll(getUnresolvedCxxPlatform().getParseTimeDeps(targetConfiguration));
    deps.addAll(getUnresolvedSwiftPlatform().getParseTimeDeps(targetConfiguration));
    deps.addAll(
        getStaticallyResolvedInstance()
            .getCodesignProvider()
            .getParseTimeDeps(targetConfiguration));
    return deps.build();
  }

  @Override
  public AppleCxxPlatform resolve(BuildRuleResolver ruleResolver) {
    return getStaticallyResolvedInstance();
  }

  @Override
  public UnresolvedCxxPlatform getUnresolvedCxxPlatform() {
    return new StaticUnresolvedCxxPlatform(getStaticallyResolvedInstance().getCxxPlatform());
  }

  @Override
  public UnresolvedSwiftPlatform getUnresolvedSwiftPlatform() {
    return StaticUnresolvedSwiftPlatform.of(
        getStaticallyResolvedInstance().getSwiftPlatform(), getFlavor());
  }

  public static StaticUnresolvedAppleCxxPlatform of(
      AppleCxxPlatform appleCxxPlatform, Flavor flavor) {
    return ImmutableStaticUnresolvedAppleCxxPlatform.of(appleCxxPlatform, flavor);
  }
}
