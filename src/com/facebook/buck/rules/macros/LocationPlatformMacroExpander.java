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

package com.facebook.buck.rules.macros;

import com.facebook.buck.core.macros.MacroException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetWithOutputs;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.cxx.toolchain.CxxPlatform;

/** Expands to the path of a build rules output. */
public class LocationPlatformMacroExpander
    extends AbstractLocationMacroExpander<LocationPlatformMacro> {

  private final CxxPlatform cxxPlatform;

  public LocationPlatformMacroExpander(CxxPlatform cxxPlatform) {
    super(LocationPlatformMacro.class);
    this.cxxPlatform = cxxPlatform;
  }

  @Override
  public Class<LocationPlatformMacro> getInputClass() {
    return LocationPlatformMacro.class;
  }

  @Override
  protected BuildRule resolve(ActionGraphBuilder graphBuilder, LocationPlatformMacro input)
      throws MacroException {
    BuildTarget target =
        input
            .getTarget()
            .withAppendedFlavors(input.getFlavors())
            .withAppendedFlavors(cxxPlatform.getFlavor());
    graphBuilder.requireRule(target);
    return super.resolve(
        graphBuilder,
        input.withTargetWithOutputs(
            BuildTargetWithOutputs.of(target, input.getTargetWithOutputs().getOutputLabel())));
  }
}
