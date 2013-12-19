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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractBuildable;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.util.BuckConstant;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Collection;

/**
 * Copies the image, sound, NIB/XIB, and other resources
 * of an iOS or OS X library.
 * <p>
 * Example rule:
 * <pre>
 * ios_resource(
 *   name = 'res',
 *   resources = glob(['Resources/**']),
 * )
 * </pre>
 */
public class AppleResource extends AbstractBuildable {

  private final ImmutableSortedSet<Path> resources;
  private final Path outputDirectory;

  @VisibleForTesting
  AppleResource(
      BuildRuleParams params,
      AppleResourceDescriptionArg args,
      Optional<Path> outputPathSubdirectory) {
    this.resources = ImmutableSortedSet.copyOf(args.resources);
    Preconditions.checkNotNull(outputPathSubdirectory);
    BuildTarget target = params.getBuildTarget();
    Path baseOutputDirectory = Paths.get(
        BuckConstant.BIN_DIR,
        target.getBasePath(),
        target.getShortName() + ".app"); // TODO: This is hokey, just a hack to get started.
    if (outputPathSubdirectory.isPresent()) {
      this.outputDirectory = baseOutputDirectory.resolve(outputPathSubdirectory.get());
    } else {
      this.outputDirectory = baseOutputDirectory;
    }
  }

  @Override
  public Collection<Path> getInputsToCompareToOutput() {
    return resources;
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) throws IOException {
    return builder.set("outputDirectory", outputDirectory.toString());
  }

  @Override
  public Path getPathToOutputFile() {
    return null;
  }

  @Override
  public List<Step> getBuildSteps(BuildContext context, BuildableContext buildableContext)
      throws IOException {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    for (Path resourcePath : resources) {
      steps.add(
          new CopyStep(
              resourcePath,
              outputDirectory,
              /* shouldRecurse */ true));
    }

    return steps.build();
  }
}
