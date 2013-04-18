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

package com.facebook.buck.rules;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.shell.Command;
import com.facebook.buck.util.DefaultDirectoryTraverser;
import com.facebook.buck.util.DirectoryTraverser;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;


/**
 * An object that represents the resources prebuilt native library.
 * <p>
 * Suppose this were a rule defined in <code>src/com/facebook/feed/BUILD</code>:
 * <pre>
 * prebuild_native_library(
 *   name = 'face_dot_com',
 *   native_libs = 'nativelibs',
 * )
 * </pre>
 */
public class PrebuiltNativeLibraryBuildRule extends AbstractCachingBuildRule {

  private final DirectoryTraverser directoryTraverser;

  private final String nativeLibs;

  protected PrebuiltNativeLibraryBuildRule(BuildRuleParams buildRuleParams,
      String nativeLibs,
      DirectoryTraverser directoryTraverser) {
    super(buildRuleParams);
    this.directoryTraverser = Preconditions.checkNotNull(directoryTraverser);
    this.nativeLibs = nativeLibs;
  }

  @Override
  protected RuleKey.Builder ruleKeyBuilder() {
    return super.ruleKeyBuilder()
        .set("nativeLibs", nativeLibs);
  }

  @Override
  protected List<String> getInputsToCompareToOutput(BuildContext context) {
    ImmutableList.Builder<String> inputsToConsiderForCachingPurposes = ImmutableList.builder();

    addInputsToList(nativeLibs, inputsToConsiderForCachingPurposes, directoryTraverser);

    return inputsToConsiderForCachingPurposes.build();
  }

  @Override
  protected List<Command> buildInternal(BuildContext context)
      throws IOException {
    // We're checking in prebuilt libraries for now, so this is a noop.
    return ImmutableList.of();
  }

  public String getNativeLibs() {
    return nativeLibs;
  }

  @Override
  public BuildRuleType getType() {
    return BuildRuleType.PREBUILT_NATIVE_LIBRARY;
  }

  public static Builder newPrebuiltNativeLibrary() {
    return new Builder();
  }

  public static class Builder extends AbstractBuildRuleBuilder {

    @Nullable
    private String nativeLibs = null;

    private Builder() {}

    @Override
    public PrebuiltNativeLibraryBuildRule build(Map<String, BuildRule> buildRuleIndex) {
      return new PrebuiltNativeLibraryBuildRule(createBuildRuleParams(buildRuleIndex),
          nativeLibs,
          new DefaultDirectoryTraverser());
    }

    @Override
    public Builder setBuildTarget(BuildTarget buildTarget) {
      super.setBuildTarget(buildTarget);
      return this;
    }

    @Override
    public Builder addDep(String dep) {
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
