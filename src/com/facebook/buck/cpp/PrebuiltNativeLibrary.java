/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.cpp;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.NativeLibraryBuildable;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.DefaultDirectoryTraverser;
import com.facebook.buck.util.DirectoryTraverser;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.io.IOException;
import java.util.List;

import javax.annotation.Nullable;


/**
 * An object that represents the resources prebuilt native library.
 * <p>
 * Suppose this were a rule defined in <code>src/com/facebook/feed/BUILD</code>:
 * <pre>
 * prebuild_native_library(
 *   name = 'face_dot_com',
 *   native_libs = 'nativeLibs',
 * )
 * </pre>
 */
public class PrebuiltNativeLibrary extends NativeLibraryBuildable {

  private final DirectoryTraverser directoryTraverser;

  protected PrebuiltNativeLibrary(BuildRuleParams buildRuleParams,
                                  String nativeLibs,
                                  boolean isAsset,
                                  DirectoryTraverser directoryTraverser) {
    super(buildRuleParams, isAsset, nativeLibs);
    this.directoryTraverser = Preconditions.checkNotNull(directoryTraverser);
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) throws IOException {
    return builder
        .set("nativeLibs", getLibraryPath())
        .set("is_asset", isAsset());
  }

  @Override
  public Iterable<String> getInputsToCompareToOutput() {
    ImmutableSortedSet.Builder<String> inputsToConsiderForCachingPurposes = ImmutableSortedSet
        .naturalOrder();

    addInputsToSortedSet(getLibraryPath(), inputsToConsiderForCachingPurposes, directoryTraverser);

    return inputsToConsiderForCachingPurposes.build();
  }

  @Override
  protected List<Step> buildArchive(BuildContext context) {
    // We're checking in prebuilt libraries for now, so this is a noop.
    return ImmutableList.of();
  }

  public static Builder newPrebuiltNativeLibrary(AbstractBuildRuleBuilderParams params) {
    return new Builder(params);
  }

  public static class Builder extends NativeLibraryBuildable.Builder {
    @Nullable
    private String nativeLibs = null;

    private Builder(AbstractBuildRuleBuilderParams params) {
      super(params);
    }

    @Override
    protected BuildRuleType getType() {
      return BuildRuleType.PREBUILT_NATIVE_LIBRARY;
    }

    @Override
    protected PrebuiltNativeLibrary newBuildable(BuildRuleParams params,
        BuildRuleResolver resolver) {
      return new PrebuiltNativeLibrary(params,
          this.nativeLibs,
          this.isAsset,
          new DefaultDirectoryTraverser());
    }

    @Override
    public Builder setBuildTarget(BuildTarget buildTarget) {
      super.setBuildTarget(buildTarget);
      return this;
    }

    @Override
    public Builder addDep(BuildTarget dep) {
      super.addDep(dep);
      return this;
    }

    @Override
    public Builder addVisibilityPattern(BuildTargetPattern visibilityPattern) {
      super.addVisibilityPattern(visibilityPattern);
      return this;
    }

    public Builder setNativeLibsDirectory(String nativeLibs) {
      this.nativeLibs = nativeLibs;
      return this;
    }

  }
}
