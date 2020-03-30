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

package com.facebook.buck.swift.toolchain;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.FlavorConvertible;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.BuildRuleResolver;
import java.util.Optional;

/**
 * Used by descriptions to properly handle {@link SwiftPlatform}. During parsing/configuration only
 * information about parse-time deps is available. During action graph creation, this can be
 * resolved to the final {@link SwiftPlatform}.
 */
public interface UnresolvedSwiftPlatform extends FlavorConvertible {
  /** Returns the parse time deps of this platform. */
  Iterable<BuildTarget> getParseTimeDeps(TargetConfiguration targetConfiguration);

  /** Returns the resolved @{link NdkCxxPlatform}. */
  Optional<SwiftPlatform> resolve(BuildRuleResolver ruleResolver);
}
