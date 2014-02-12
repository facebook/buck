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
import com.facebook.buck.rules.Buildables;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.DirectoryTraverser;
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
 *   dirs = ['MyLibrary.bundle'],
 *   files = glob(['Resources/**']),
 * )
 * </pre>
 */
public class AppleResource extends AbstractBuildable {

  private final DirectoryTraverser directoryTraverser;
  private final ImmutableSortedSet<Path> dirs;
  private final ImmutableSortedSet<Path> files;
  private final Path outputDirectory;

  AppleResource(
      DirectoryTraverser directoryTraverser,
      BuildRuleParams params,
      AppleResourceDescriptionArg args,
      Optional<Path> outputPathSubdirectory) {
    this.directoryTraverser = Preconditions.checkNotNull(directoryTraverser);
    this.dirs = ImmutableSortedSet.copyOf(args.dirs);
    this.files = ImmutableSortedSet.copyOf(args.files);
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

  /**
   * Returns the set of directories to recursively copy for this resource rule.
   */
  public ImmutableSortedSet<Path> getDirs() {
    return dirs;
  }

  /**
   * Returns the set of files to copy for this resource rule.
   */
  public ImmutableSortedSet<Path> getFiles() {
    return files;
  }

  @Override
  public Collection<Path> getInputsToCompareToOutput() {
    ImmutableSortedSet.Builder<Path> inputsToConsiderForCachingPurposes = ImmutableSortedSet
        .naturalOrder();

    for (Path dir : dirs) {
      Buildables.addInputsToSortedSet(
          dir,
          inputsToConsiderForCachingPurposes,
          directoryTraverser);
    }

    inputsToConsiderForCachingPurposes.addAll(files);
    return inputsToConsiderForCachingPurposes.build();
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

    for (Path dir : dirs) {
      steps.add(
          CopyStep.forDirectory(
              dir,
              outputDirectory,
              CopyStep.DirectoryMode.DIRECTORY_AND_CONTENTS));
    }

    for (Path file : files) {
      steps.add(CopyStep.forFile(file, outputDirectory));
    }

    return steps.build();
  }
}
