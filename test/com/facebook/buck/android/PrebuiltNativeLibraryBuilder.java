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
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

public class PrebuiltNativeLibraryBuilder {

  private PrebuiltNativeLibraryBuilder() {
    // Utility class.
  }

  public static Builder newBuilder(BuildTarget buildTarget) {
    return new Builder(buildTarget);
  }

  public static class Builder {

    private BuildTarget buildTarget;
    private Path nativeLibs;
    private boolean isAsset;

    public Builder(BuildTarget buildTarget) {
      this.buildTarget = buildTarget;
    }

    public Builder setNativeLibs(Path nativeLibs) {
      this.nativeLibs = nativeLibs;
      return this;
    }

    public Builder setIsAsset(boolean isAsset) {
      this.isAsset = isAsset;
      return this;
    }

    public PrebuiltNativeLibrary build() {
      return new PrebuiltNativeLibrary(
          new FakeBuildRuleParamsBuilder(buildTarget).build(),
          nativeLibs,
          isAsset,
          ImmutableSortedSet.<Path>of());
    }
  }
}
