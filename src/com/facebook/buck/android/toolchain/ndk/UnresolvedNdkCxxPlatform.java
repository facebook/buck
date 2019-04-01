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
package com.facebook.buck.android.toolchain.ndk;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.UnresolvedCxxPlatform;

/**
 * Used by descriptions to properly handle {@link NdkCxxPlatform}. During parsing/configuration only
 * information about parse-time deps is available. During action graph creation, this can be
 * resolved to the final {@link NdkCxxPlatform}.
 */
public interface UnresolvedNdkCxxPlatform {
  /** Returns the parse time deps of this platform. */
  Iterable<BuildTarget> getParseTimeDeps(TargetConfiguration targetConfiguration);

  /** Returns the resolved @{link NdkCxxPlatform}. */
  NdkCxxPlatform resolve(BuildRuleResolver ruleResolver);

  /**
   * Returns the @{link UnresolvedCxxPlatform} corresponding to the resolved {@link
   * NdkCxxPlatform}'s {@link CxxPlatform}.
   */
  UnresolvedCxxPlatform getCxxPlatform();
}
