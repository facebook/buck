/*
 * Copyright 2018-present Facebook, Inc.
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
import com.facebook.buck.core.rules.BuildRuleResolver;

/**
 * Used to provide a {@link CxxPlatform} that is fully specified before parsing/configuration
 * (specified in .buckconfig, for example).
 */
public class StaticUnresolvedCxxPlatform implements UnresolvedCxxPlatform {
  private final CxxPlatform cxxPlatform;

  public StaticUnresolvedCxxPlatform(CxxPlatform cxxPlatform) {
    this.cxxPlatform = cxxPlatform;
  }

  @Override
  public CxxPlatform resolve(BuildRuleResolver resolver) {
    // TODO(cjhopman): Should we verify that some of the rules are actually present in the graph at
    // this point? We might be able to give a more informative message.
    return cxxPlatform;
  }

  public CxxPlatform getCxxPlatform() {
    return cxxPlatform;
  }

  @Override
  public Flavor getFlavor() {
    return cxxPlatform.getFlavor();
  }

  @Override
  public UnresolvedCxxPlatform withFlavor(Flavor flavor) {
    return new StaticUnresolvedCxxPlatform(cxxPlatform.withFlavor(flavor));
  }

  @Override
  public Iterable<BuildTarget> getParseTimeDeps(TargetConfiguration targetConfiguration) {
    return CxxPlatforms.getParseTimeDeps(targetConfiguration, cxxPlatform);
  }

  @Override
  public Iterable<? extends BuildTarget> getLinkerParseTimeDeps(
      TargetConfiguration targetConfiguration) {
    return cxxPlatform.getLd().getParseTimeDeps(targetConfiguration);
  }
}
