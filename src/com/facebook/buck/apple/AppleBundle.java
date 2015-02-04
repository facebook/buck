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

package com.facebook.buck.apple;

import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.coercer.Either;
import com.facebook.buck.step.Step;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;

import javax.annotation.Nullable;

/**
 * Creates a bundle: a directory containing a binary and resources, described by an Info.plist.
 */
public class AppleBundle extends AbstractBuildRule {

  private final Either<AppleBundleExtension, String> extension;
  private final Optional<SourcePath> infoPlist;
  private final BuildRule binary;
  private final Optional<String> xcodeProductType;

  AppleBundle(
      BuildRuleParams params,
      SourcePathResolver resolver,
      Either<AppleBundleExtension, String> extension,
      Optional<SourcePath> infoPlist,
      BuildRule binary,
      Optional<String> xcodeProductType) {
    super(params, resolver);
    this.extension = extension;
    this.infoPlist = infoPlist;
    this.binary = binary;
    this.xcodeProductType = xcodeProductType;
  }

  /**
   * Returns the bundle's extension as a known value, if it is known.
   */
  public Optional<AppleBundleExtension> getExtensionValue() {
    if (extension.isLeft()) {
      return Optional.of(extension.getLeft());
    } else {
      return Optional.absent();
    }
  }

  /**
   * Returns the bundle's extension as a string.
   */
  public String getExtensionString() {
    return extension.isLeft() ? extension.getLeft().toFileExtension() : extension.getRight();
  }

  /**
   * Returns the path to the Info.plist.
   */
  public Optional<SourcePath> getInfoPlist() {
    return infoPlist;
  }

  /**
   * Returns the binary inside the bundle.
   */
  public BuildRule getBinary() {
    return binary;
  }

  /**
   * If present, the value to use for the productType field of a generated
   * Xcode project's PBXNativeTarget entry for this target.
   *
   * If not present, will be automatically generated based on the extension
   * of this bundle.
   */
  public Optional<String> getXcodeProductType() {
    return xcodeProductType;
  }

  @Override
  public ImmutableCollection<Path> getInputsToCompareToOutput() {
    return ImmutableList.of();
  }

  @Override
  @Nullable
  public Path getPathToOutputFile() {
    return null;
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder.setReflectively("extension", getExtensionString());
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    return ImmutableList.of();
  }
}
