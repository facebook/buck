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

package com.facebook.buck.features.lua;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorConvertible;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkStrategy;

/**
 * Used by descriptions to properly handle {@link LuaPlatform}. During parsing/configuration only
 * information about parse-time deps is available. During action graph creation, this can be
 * resolved to the final {@link LuaPlatform}.
 */
public interface UnresolvedLuaPlatform extends FlavorConvertible {
  /** Resolves the platform. */
  LuaPlatform resolve(BuildRuleResolver resolver, TargetConfiguration targetConfiguration);

  @Override
  Flavor getFlavor();

  /** Returns the parse-time deps required for this platform. */
  Iterable<BuildTarget> getParseTimeDeps(TargetConfiguration targetConfiguration);

  /** Returns the natove link strategy used by this platform. */
  // TODO(agallgaher): Instead of exposing this, we should just encapsulate this inside
  // `getParseTimeDeps()`.
  NativeLinkStrategy getNativeLinkStrategy();
}
