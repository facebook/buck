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

package com.facebook.buck.externalactions.android;

import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.external.model.ExternalAction;
import com.facebook.buck.externalactions.utils.ExternalActionsUtils;
import com.facebook.buck.rules.modern.model.BuildableCommand;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.step.isolatedsteps.android.ExtractFromAndroidManifestStep;
import com.facebook.buck.step.isolatedsteps.android.MiniAapt;
import com.facebook.buck.step.isolatedsteps.common.MakeCleanDirectoryIsolatedStep;
import com.facebook.buck.step.isolatedsteps.common.TouchStep;
import com.facebook.buck.step.isolatedsteps.common.WriteFileIsolatedStep;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.util.List;
import java.util.Objects;

/**
 * {@link ExternalAction} that returns the build steps for {@link
 * com.facebook.buck.android.AndroidResource}.
 */
public class AndroidResourceExternalAction implements ExternalAction {

  private static final int NUM_EXPECTED_FILES = 1;

  @Override
  public ImmutableList<IsolatedStep> getSteps(BuildableCommand buildableCommand) {
    List<String> json = buildableCommand.getExtraFilesList();
    Preconditions.checkState(
        json.size() == NUM_EXPECTED_FILES,
        "Expected %s JSON files, got %s",
        NUM_EXPECTED_FILES,
        json.size());
    AndroidResourceExternalActionArgs args =
        ExternalActionsUtils.readJsonArgs(
            Iterables.getOnlyElement(json), AndroidResourceExternalActionArgs.class);

    String rDotJavaPackageArgument = args.getRDotJavaPackageArgument();

    ImmutableList.Builder<IsolatedStep> steps = ImmutableList.builder();
    steps.addAll(MakeCleanDirectoryIsolatedStep.of(RelPath.get(args.getPathToTextSymbolsDir())));
    if (args.getResPath() == null) {
      return steps
          .add(new TouchStep(RelPath.get(args.getPathToTextSymbolsFile())))
          .add(
              WriteFileIsolatedStep.of(
                  rDotJavaPackageArgument == null ? "" : rDotJavaPackageArgument,
                  RelPath.get(args.getPathToRDotJavaPackageFile()),
                  false /* executable */))
          .build();
    }

    // If the 'package' was not specified for this android_resource(), then attempt to parse it
    // from the AndroidManifest.xml.
    if (rDotJavaPackageArgument == null) {
      Objects.requireNonNull(
          args.getPathToManifestFile(),
          "manifestFile cannot be null when res is non-null and rDotJavaPackageArgument is "
              + "null. This should already be enforced by the constructor.");
      steps.add(
          new ExtractFromAndroidManifestStep(
              RelPath.get(args.getPathToManifestFile()),
              RelPath.get(args.getPathToRDotJavaPackageFile())));
    } else {
      steps.add(
          WriteFileIsolatedStep.of(
              rDotJavaPackageArgument,
              RelPath.get(args.getPathToRDotJavaPackageFile()),
              false /* executable */));
    }

    ImmutableSet<RelPath> pathsToSymbolsOfDeps =
        args.getPathsToSymbolsOfDeps().stream()
            .map(RelPath::get)
            .collect(ImmutableSet.toImmutableSet());
    steps.add(
        new MiniAapt(
            RelPath.get(args.getResPath()),
            RelPath.get(args.getPathToTextSymbolsFile()),
            pathsToSymbolsOfDeps));
    return steps.build();
  }
}
