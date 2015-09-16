/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.java;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

public class CalculateAbi extends AbstractBuildRule implements SupportsInputBasedRuleKey {

  public static final Flavor FLAVOR = ImmutableFlavor.of("abi");

  @AddToRuleKey
  private final SourcePath binaryJar;

  public CalculateAbi(
      BuildRuleParams buildRuleParams,
      SourcePathResolver resolver,
      SourcePath binaryJar) {
    super(buildRuleParams, resolver);
    this.binaryJar = binaryJar;
  }

  public static CalculateAbi of(
      BuildTarget target,
      SourcePathResolver pathResolver,
      BuildRuleParams libraryParams,
      SourcePath library) {
    return new CalculateAbi(
        libraryParams.copyWithChanges(
            target,
            Suppliers.ofInstance(
                ImmutableSortedSet.copyOf(pathResolver.filterBuildRuleInputs(library))),
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
        pathResolver,
        library);
  }

  private Path getAbiJarPath() {
    return BuildTargets.getGenPath(getBuildTarget(), "%s")
        .resolve(String.format("%s-abi.jar", getBuildTarget().getShortName()));
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    return ImmutableList.of(
        new MkdirStep(getProjectFilesystem(), getAbiJarPath().getParent()),
        new RmStep(getProjectFilesystem(), getAbiJarPath(), /* shouldForceDeletion */ true),
        new CalculateAbiStep(
            buildableContext,
            getProjectFilesystem(),
            getResolver().getPath(binaryJar),
            getPathToOutput()));
  }

  @Override
  public Path getPathToOutput() {
    return getAbiJarPath();
  }

}
