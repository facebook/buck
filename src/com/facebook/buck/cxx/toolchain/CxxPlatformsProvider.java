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

package com.facebook.buck.cxx.toolchain;

import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.toolchain.Toolchain;
import com.facebook.buck.core.util.immutables.BuckStyleValue;

@BuckStyleValue
public abstract class CxxPlatformsProvider implements Toolchain {

  public static final String DEFAULT_NAME = "cxx-platforms";

  public abstract UnresolvedCxxPlatform getDefaultUnresolvedCxxPlatform();

  public abstract FlavorDomain<UnresolvedCxxPlatform> getUnresolvedCxxPlatforms();

  @Override
  public String getName() {
    return DEFAULT_NAME;
  }

  public static CxxPlatformsProvider of(
      UnresolvedCxxPlatform defaultUnresolvedCxxPlatform,
      FlavorDomain<UnresolvedCxxPlatform> unresolvedCxxPlatforms) {
    return ImmutableCxxPlatformsProvider.of(defaultUnresolvedCxxPlatform, unresolvedCxxPlatforms);
  }
}
