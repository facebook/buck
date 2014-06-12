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
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.DescribedRule;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;

import javax.annotation.Nullable;

public class NdkLibraryBuilder {

  private NdkLibraryBuilder() {
    // Utility class
  }

  public static Builder createNdkLibrary(BuildTarget target) {
    return new Builder(target);
  }
  public static class Builder {
    @Nullable
    private BuildTarget buildTarget = null;
    private ImmutableSet.Builder<SourcePath> sources = ImmutableSet.builder();
    private ImmutableList.Builder<String> flags = ImmutableList.builder();
    private boolean isAsset = false;
    private Optional<String> ndkVersion = Optional.absent();

    public Builder(BuildTarget buildTarget) {
      this.buildTarget = Preconditions.checkNotNull(buildTarget);
    }

    public Builder addSrc(Path source) {
      this.sources.add(new PathSourcePath(source));
      return this;
    }

    public Builder addFlag(String flag) {
      this.flags.add(flag);
      return this;
    }

    public Builder setIsAsset(boolean isAsset) {
      this.isAsset = isAsset;
      return this;
    }

    public Builder setNdkVersion(String ndkVersion) {
      this.ndkVersion = Optional.of(ndkVersion);
      return this;
    }

    public NdkLibrary buildAsBuildable() {
      return new NdkLibrary(buildTarget, sources.build(), flags.build(), isAsset, ndkVersion);
    }

    public BuildRule build() {
      return new DescribedRule(
          NdkLibraryDescription.TYPE,
          buildAsBuildable(),
          new FakeBuildRuleParamsBuilder(buildTarget).build());
    }
  }
}
