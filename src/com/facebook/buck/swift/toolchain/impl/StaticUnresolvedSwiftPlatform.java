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

package com.facebook.buck.swift.toolchain.impl;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.swift.toolchain.SwiftPlatform;
import com.facebook.buck.swift.toolchain.UnresolvedSwiftPlatform;
import com.google.common.collect.ImmutableList;
import java.util.Optional;

/**
 * Used to provide a {@link SwiftPlatform} that is fully specified before parsing/configuration
 * (specified in .buckconfig, for example).
 */
@BuckStyleValue
public abstract class StaticUnresolvedSwiftPlatform implements UnresolvedSwiftPlatform {

  public abstract Optional<SwiftPlatform> getStaticallyResolvedInstance();

  @Override
  public abstract Flavor getFlavor();

  @Override
  public Iterable<BuildTarget> getParseTimeDeps(TargetConfiguration targetConfiguration) {
    // SwiftPlatform doesn't have any ToolProviders
    return ImmutableList.of();
  }

  @Override
  public Optional<SwiftPlatform> resolve(BuildRuleResolver ruleResolver) {
    return getStaticallyResolvedInstance();
  }

  public static StaticUnresolvedSwiftPlatform of(
      Optional<? extends SwiftPlatform> staticallyResolvedInstance, Flavor flavor) {
    return ImmutableStaticUnresolvedSwiftPlatform.of(staticallyResolvedInstance, flavor);
  }
}
