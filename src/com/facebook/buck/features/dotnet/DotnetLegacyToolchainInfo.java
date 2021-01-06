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

package com.facebook.buck.features.dotnet;

import com.facebook.buck.core.rules.providers.annotations.ImmutableInfo;
import com.facebook.buck.core.rules.providers.impl.BuiltInProvider;
import com.facebook.buck.core.rules.providers.impl.BuiltInProviderInfo;
import com.facebook.buck.core.rules.providers.lib.RunInfo;

/** Information to make the C#/.NET compiler available to the rule analysis graph */
@ImmutableInfo(args = {"compiler"})
public abstract class DotnetLegacyToolchainInfo
    extends BuiltInProviderInfo<DotnetLegacyToolchainInfo> {

  public static final BuiltInProvider<DotnetLegacyToolchainInfo> PROVIDER =
      BuiltInProvider.of(ImmutableDotnetLegacyToolchainInfo.class);

  /** @return information to invoke the .net compiler */
  public abstract RunInfo compiler();
}
