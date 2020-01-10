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

package com.facebook.buck.apple;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.AbstractNodeBuilder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.types.Either;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;

public class AppleBundleBuilder
    extends AbstractNodeBuilder<
        AppleBundleDescriptionArg.Builder,
        AppleBundleDescriptionArg,
        AppleBundleDescription,
        AppleBundle> {

  protected AppleBundleBuilder(BuildTarget target) {
    super(FakeAppleRuleDescriptions.BUNDLE_DESCRIPTION, target);
  }

  protected AppleBundleBuilder(BuildTarget target, ProjectFilesystem projectFilesystem) {
    super(FakeAppleRuleDescriptions.BUNDLE_DESCRIPTION, target, projectFilesystem);
  }

  public static AppleBundleBuilder createBuilder(BuildTarget target) {
    return new AppleBundleBuilder(target);
  }

  public static AppleBundleBuilder createBuilder(
      BuildTarget target, ProjectFilesystem projectFilesystem) {
    return new AppleBundleBuilder(target, projectFilesystem);
  }

  public AppleBundleBuilder setExtension(Either<AppleBundleExtension, String> extension) {
    getArgForPopulating().setExtension(extension);
    return this;
  }

  public AppleBundleBuilder setProductName(Optional<String> productName) {
    getArgForPopulating().setProductName(productName);
    return this;
  }

  public AppleBundleBuilder setXcodeProductType(Optional<String> xcodeProductType) {
    getArgForPopulating().setXcodeProductType(xcodeProductType);
    return this;
  }

  public AppleBundleBuilder setBinary(BuildTarget binary) {
    getArgForPopulating().setBinary(binary);
    return this;
  }

  public AppleBundleBuilder setInfoPlist(SourcePath infoPlist) {
    getArgForPopulating().setInfoPlist(infoPlist);
    return this;
  }

  public AppleBundleBuilder setDeps(ImmutableSortedSet<BuildTarget> deps) {
    getArgForPopulating().setDeps(deps);
    return this;
  }

  public AppleBundleBuilder setTests(ImmutableSortedSet<BuildTarget> tests) {
    getArgForPopulating().setTests(tests);
    return this;
  }

  public AppleBundleBuilder setInfoPlistSubstitutions(
      ImmutableMap<String, String> infoPlistSubstitutions) {
    getArgForPopulating().setInfoPlistSubstitutions(infoPlistSubstitutions);
    return this;
  }
}
