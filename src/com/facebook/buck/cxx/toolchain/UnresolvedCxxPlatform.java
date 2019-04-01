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
import com.facebook.buck.core.model.FlavorConvertible;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.google.common.base.Verify;

/**
 * Used by descriptions to properly handle {@link CxxPlatform}. During parsing/configuration only
 * information about parse-time deps is available. During action graph creation, this can be
 * resolved to the final {@link CxxPlatform}.
 */
public interface UnresolvedCxxPlatform extends FlavorConvertible {
  /** Resolves the platform. */
  CxxPlatform resolve(BuildRuleResolver resolver);

  @Override
  Flavor getFlavor();

  /**
   * Returns this provider as a different flavor. This might only make sense for
   * .buckconfig-configured cxx platforms and be removed in the future.
   */
  UnresolvedCxxPlatform withFlavor(Flavor hostFlavor);

  /** Returns the parse-time deps required for this platform. */
  Iterable<BuildTarget> getParseTimeDeps(TargetConfiguration targetConfiguration);

  /**
   * This probably shouldn't exist. Users probably shouldn't be able to specify specific individual
   * pieces of the platform that they want to use. If you're tempted to use this, use
   * getParseTimeDeps() instead or update this comment with your valid use case.
   */
  // TODO(cjhopman): delete this.
  Iterable<? extends BuildTarget> getLinkerParseTimeDeps(TargetConfiguration targetConfiguration);

  /**
   * This definitely shouldn't exist. Currently, there are cases where we require resolving the
   * CxxPlatform before graph construction. This is mostly just for other platform-like things that
   * reference the cxx one.
   */
  // TODO(cjhopman): delete this.
  default CxxPlatform getLegacyTotallyUnsafe() {
    Verify.verify(
        this instanceof StaticUnresolvedCxxPlatform,
        "Resolving the CxxPlatform without a BuildRuleResolver is totally unsupported. "
            + "It only sort of happens to work with static cxx platforms.");
    return ((StaticUnresolvedCxxPlatform) this).getCxxPlatform();
  }
}
