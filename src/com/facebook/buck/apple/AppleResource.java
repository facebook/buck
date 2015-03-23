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

import com.facebook.buck.io.DirectoryTraverser;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * Copies the image, sound, NIB/XIB, and other resources
 * of an iOS or OS X library.
 * <p>
 * Example rule:
 * <pre>
 * apple_resource(
 *   name = 'res',
 *   dirs = ['MyLibrary.bundle'],
 *   files = glob(['Resources/**']),
 *   variants = {
 *     'Resources/Localizable.strings' : {
 *       'en' : 'Resources/en.lproj/Localizable.strings',
 *       'fr' : 'Resources/fr.lproj/Localizable.strings',
 *     },
 *   },
 * )
 * </pre>
 */
public class AppleResource extends AbstractBuildRule {

  private final DirectoryTraverser directoryTraverser;
  private final ImmutableSortedSet<Path> dirs;
  private final ImmutableSortedSet<SourcePath> files;
  private final ImmutableMap<String, ImmutableMap<String, SourcePath>> variants;
  private final Path outputDirectory;

  AppleResource(
      BuildRuleParams params,
      SourcePathResolver resolver,
      DirectoryTraverser directoryTraverser,
      AppleResourceDescription.Arg args) {
    super(params, resolver);
    this.directoryTraverser = directoryTraverser;
    this.dirs = ImmutableSortedSet.copyOf(args.dirs);
    this.files = ImmutableSortedSet.copyOf(args.files);

    if (args.variants.isPresent()) {
      Map<String, Map<String, SourcePath>> variants = args.variants.get();
      ImmutableMap.Builder<String, ImmutableMap<String, SourcePath>> variantsBuilder =
          ImmutableMap.builder();
      for (String path : variants.keySet()) {
        variantsBuilder.put(path, ImmutableMap.copyOf(variants.get(path)));
      }
      this.variants = variantsBuilder.build();
    } else {
      this.variants = ImmutableMap.of();
    }

    BuildTarget target = params.getBuildTarget();
    // TODO(user): This is hokey, just a hack to get started.
    // TODO(grp): Support copying into a bundle's resources subdirectory.
    this.outputDirectory = BuildTargets.getScratchPath(target, "%s.app");
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
  public ImmutableSortedSet<SourcePath> getFiles() {
    return files;
  }

  @Override
  public ImmutableCollection<Path> getInputsToCompareToOutput() {
    ImmutableSortedSet.Builder<Path> inputsToConsiderForCachingPurposes = ImmutableSortedSet
        .naturalOrder();

    for (Path dir : dirs) {
      BuildRules.addInputsToSortedSet(
          dir,
          inputsToConsiderForCachingPurposes,
          directoryTraverser);
    }

    for (String virtualPathName : variants.keySet()) {
      Map<String, SourcePath> variant =
          Preconditions.checkNotNull(variants.get(virtualPathName));
      inputsToConsiderForCachingPurposes.addAll(
          getResolver().filterInputsToCompareToOutput(variant.values()));
    }

    inputsToConsiderForCachingPurposes.addAll(getResolver().filterInputsToCompareToOutput(files));
    return inputsToConsiderForCachingPurposes.build();
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder;
  }

  @Override
  @Nullable
  public Path getPathToOutputFile() {
    return null;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {

    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    steps.add(new MakeCleanDirectoryStep(outputDirectory));

    for (Path dir : dirs) {
      steps.add(
          CopyStep.forDirectory(
              dir,
              outputDirectory,
              CopyStep.DirectoryMode.DIRECTORY_AND_CONTENTS));
    }

    for (SourcePath file : files) {
      Path path = getResolver().getPath(file);
      steps.add(CopyStep.forFile(path, outputDirectory.resolve(path.getFileName())));
    }

    // TODO(grp): Support copying variant resources like Xcode.

    return steps.build();
  }
}
