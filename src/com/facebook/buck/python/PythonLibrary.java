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

package com.facebook.buck.python;

import static com.facebook.buck.rules.BuildableProperties.Kind.LIBRARY;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractBuildable;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.SymlinkFileStep;
import com.facebook.buck.util.BuckConstant;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import javax.annotation.Nullable;

public class PythonLibrary extends AbstractBuildable {

  private final static BuildableProperties OUTPUT_TYPE = new BuildableProperties(LIBRARY);
  private final BuildTarget buildTarget;
  private final ImmutableSortedSet<SourcePath> srcs;
  private final Path pythonPathDirectory;

  protected PythonLibrary(BuildRuleParams buildRuleParams,
      ImmutableSortedSet<SourcePath> srcs) {
    this.buildTarget = buildRuleParams.getBuildTarget();
    Preconditions.checkNotNull(srcs);
    Preconditions.checkArgument(!srcs.isEmpty(),
        "Must specify srcs for %s.",
        buildRuleParams.getBuildTarget());
    this.srcs = srcs;
    this.pythonPathDirectory = getPathToPythonPathDirectory();
  }

  @Nullable
  @Override
  public String getPathToOutputFile() {
    return null;
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) throws IOException {
    return builder
        .set("srcs", ImmutableSortedSet.copyOf(Iterables.transform(srcs, SourcePath.TO_REFERENCE)));
  }

  private Path getPathToPythonPathDirectory() {
    return Paths.get(
        BuckConstant.GEN_DIR,
        buildTarget.getBasePath(),
        getPathUnderGenDirectory());
  }

  private String getPathUnderGenDirectory() {
    return "__pylib_" + buildTarget.getShortName();
  }

  @Override
  public Iterable<String> getInputsToCompareToOutput() {
    return SourcePaths.filterInputsToCompareToOutput(srcs);
  }

  /**
   * @return The directory that must be added to the {@code PYTHONPATH} to include the sources from
   *     this rule when running Python.
   */
  public Path getPythonPathDirectory() {
    return pythonPathDirectory;
  }

  @Override
  public List<Step> getBuildSteps(BuildContext context, BuildableContext buildableContext)
      throws IOException {
    ImmutableList.Builder<Step> commands = ImmutableList.builder();

    // Copy all of the sources to a generated directory so that the generated directory can be
    // included as a $PYTHONPATH element. TODO(mbolin): Symlinks would be more efficient, but we
    // need to include this structure in the artifact, which is not guaranteed to be zip-friendly.
    commands.add(new MakeCleanDirectoryStep(pythonPathDirectory));

    ImmutableSortedSet.Builder<Path> directories = ImmutableSortedSet.naturalOrder();
    ImmutableList.Builder<Step> symlinkSteps = ImmutableList.builder();

    for (SourcePath src : srcs) {
      Path srcPath = src.resolve(context);
      Path targetPath = pythonPathDirectory.resolve(srcPath);

      directories.add(targetPath.getParent());
      symlinkSteps.add(
          new SymlinkFileStep(srcPath.toString(),
              targetPath.toString(),
              /* useAbsolutePaths */ false));

      buildableContext.recordArtifact(targetPath);
    }

    for (Path path : directories.build()) {
      commands.add(new MkdirStep(path));
    }

    commands.addAll(symlinkSteps.build());

    return commands.build();
  }

  @Override
  public BuildableProperties getProperties() {
    return OUTPUT_TYPE;
  }
}
