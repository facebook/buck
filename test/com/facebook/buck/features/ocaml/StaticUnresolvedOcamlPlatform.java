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

package com.facebook.buck.features.ocaml;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.cxx.toolchain.impl.CxxPlatforms;

public class StaticUnresolvedOcamlPlatform implements UnresolvedOcamlPlatform {

  private final OcamlPlatform ocamlPlatform;

  public StaticUnresolvedOcamlPlatform(OcamlPlatform ocamlPlatform) {
    this.ocamlPlatform = ocamlPlatform;
  }

  @Override
  public OcamlPlatform resolve(
      BuildRuleResolver resolver, TargetConfiguration targetConfiguration) {
    return ocamlPlatform;
  }

  @Override
  public Flavor getFlavor() {
    return ocamlPlatform.getFlavor();
  }

  @Override
  public Iterable<BuildTarget> getParseTimeDeps(TargetConfiguration targetConfiguration) {
    return CxxPlatforms.getParseTimeDeps(targetConfiguration, ocamlPlatform.getCxxPlatform());
  }
}
