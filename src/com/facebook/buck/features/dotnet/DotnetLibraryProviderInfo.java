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

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.rules.providers.annotations.ImmutableInfo;
import com.facebook.buck.core.rules.providers.impl.BuiltInProvider;
import com.facebook.buck.core.rules.providers.impl.BuiltInProviderInfo;

@ImmutableInfo(args = {"dll"})
public abstract class DotnetLibraryProviderInfo
    extends BuiltInProviderInfo<DotnetLibraryProviderInfo> {

  public static final BuiltInProvider<DotnetLibraryProviderInfo> PROVIDER =
      BuiltInProvider.of(ImmutableDotnetLibraryProviderInfo.class);

  public abstract Artifact dll();
}
