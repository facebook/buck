/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.android;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

import javax.annotation.Nullable;

public class PrebuiltNativeLibraryBuilder
    extends AbstractNodeBuilder<PrebuiltNativeLibraryDescription.Arg> {

  private PrebuiltNativeLibraryBuilder(BuildTarget target) {
    super(new PrebuiltNativeLibraryDescription(), target);
  }

  public static PrebuiltNativeLibraryBuilder newBuilder(BuildTarget buildTarget) {
    return new PrebuiltNativeLibraryBuilder(buildTarget);
  }

  public PrebuiltNativeLibraryBuilder setIsAsset(@Nullable Boolean isAsset) {
    arg.isAsset = Optional.fromNullable(isAsset);
    return this;
  }

  public PrebuiltNativeLibraryBuilder setNativeLibs(@Nullable Path nativeLibs) {
    arg.nativeLibs = nativeLibs;
    return this;
  }

  public PrebuiltNativeLibraryBuilder setDeps(@Nullable ImmutableSortedSet<BuildTarget> deps) {
    arg.deps = Optional.fromNullable(deps);
    return this;
  }
}
