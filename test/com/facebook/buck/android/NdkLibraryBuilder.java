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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.testutil.FakeProjectFilesystem;
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

  public static Builder createNdkLibrary(BuildTarget target,
      SourcePathResolver resolver,
      BuildRuleResolver ruleResolver,
      ProjectFilesystem projectFilesystem) {
    return new Builder(target, resolver, ruleResolver, projectFilesystem);
  }
  public static class Builder {
    private final SourcePathResolver resolver;
    @Nullable
    private BuildTarget buildTarget = null;
    private ImmutableSet.Builder<SourcePath> sources = ImmutableSet.builder();
    private ImmutableList.Builder<String> flags = ImmutableList.builder();
    private boolean isAsset = false;
    private Optional<String> ndkVersion = Optional.absent();
    private BuildRuleResolver ruleResolver;
    private ProjectFilesystem projectFilesystem;

    public Builder(BuildTarget buildTarget, SourcePathResolver resolver,
        BuildRuleResolver ruleResolver, ProjectFilesystem projectFilesystem) {
      this.buildTarget = Preconditions.checkNotNull(buildTarget);
      this.resolver = Preconditions.checkNotNull(resolver);
      this.ruleResolver = ruleResolver;
      this.projectFilesystem = projectFilesystem;
    }

    public Builder addSrc(Path source) {
      this.sources.add(new PathSourcePath(new FakeProjectFilesystem(), source));
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

    public NdkLibrary build() {
      return new NdkLibrary(
          new FakeBuildRuleParamsBuilder(buildTarget).setType(NdkLibraryDescription.TYPE).build(),
          resolver,
          buildTarget.getBasePath().resolve("Android.mk"),
          sources.build(),
          flags.build(),
          isAsset,
          ndkVersion,
          NdkLibraryDescription.MACRO_HANDLER.getExpander(
              buildTarget,
              ruleResolver,
              projectFilesystem
          ));
    }
  }
}
