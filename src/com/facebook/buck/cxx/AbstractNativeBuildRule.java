/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.BuckConstant;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.io.Files;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Set;

/**
 * Abstract class for building native object files and libraries.
 */
public abstract class AbstractNativeBuildRule extends AbstractBuildRule {

  private static final String OBJECT_EXTENSION = ".o";
  protected static final String DEFAULT_CPP_COMPILER = "g++";
  protected static final String DEFAULT_C_COMPILER = "gcc";

  private final ImmutableSortedSet<SourcePath> srcs;
  private final ImmutableSortedSet<SourcePath> headers;
  private final ImmutableMap<SourcePath, String> perSrcFileFlags;

  public AbstractNativeBuildRule(
      BuildRuleParams params,
      ImmutableSortedSet<SourcePath> srcs,
      ImmutableSortedSet<SourcePath> headers,
      ImmutableMap<SourcePath, String> perSrcFileFlags) {
    super(params);
    this.headers = Preconditions.checkNotNull(headers);
    this.srcs = Preconditions.checkNotNull(srcs);
    this.perSrcFileFlags = Preconditions.checkNotNull(perSrcFileFlags);
  }

  /** name of the C compiler to use */
  protected abstract String getCompiler();

  /** Final linking step(s) */
  protected abstract ImmutableList<Step> getFinalBuildSteps(
      ImmutableSortedSet<Path> srcs,
      Path outputFile);

  /** String format to deduce the output file out of the target name */
  protected abstract String getOutputFileNameFormat();

  @Override
  public ImmutableCollection<Path> getInputsToCompareToOutput() {
    return SourcePaths.filterInputsToCompareToOutput(Iterables.concat(srcs, headers));
  }

  private static void addMkdirStepIfNeeded(
      Set<Path> createdDirectories,
      ImmutableList.Builder<Step> steps,
      Path directory) {
    if (createdDirectories.add(directory)) {
      steps.add(new MkdirStep(directory));
    }
  }

  private static Collection<String> commandLineArgsForFile(
      SourcePath file,
      ImmutableMap<SourcePath, String> perSrcFileFlags) {
    String srcFileFlags = perSrcFileFlags.get(file);
    if (srcFileFlags == null) {
      return ImmutableList.of();
    }
    // TODO(user): Ugh, this is terrible. We need to pass in an array everywhere, then
    // join it on space for Xcode (which takes a single string of course).
    return ImmutableList.copyOf(srcFileFlags.split(" "));
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    ImmutableSortedSet.Builder<Path> objectFiles = ImmutableSortedSet.naturalOrder();
    Set<Path> createdDirectories = Sets.newHashSet();

    addMkdirStepIfNeeded(createdDirectories, steps, getPathToOutputFile().getParent());

    for (SourcePath src : srcs) {
      Path srcFile = src.resolve();
      // We expect srcFile to be relative to the buck root
      Preconditions.checkState(!srcFile.isAbsolute());
      Path parent = srcFile.getParent();
      if (parent == null) {
        parent = Paths.get("");
      }
      // To avoid collisions, objects files are created in directories that reflects the path to
      // source files rather than the (path-like) name of build targets
      Path targetDir = BuckConstant.GEN_PATH.resolve(parent);
      addMkdirStepIfNeeded(createdDirectories, steps, targetDir);

      Path objectFile = targetDir.resolve(
          Files.getNameWithoutExtension(srcFile.getFileName().toString()) + OBJECT_EXTENSION);
      steps.add(new CompilerStep(
            /* compiler */ getCompiler(),
            /* shouldLink */ false,
            /* srcs */ ImmutableSortedSet.of(src.resolve()),
            /* outputFile */ objectFile,
            /* shouldAddProjectRootToIncludePaths */ true,
            /* includePaths */ ImmutableSortedSet.<Path>of(),
            /* commandLineArgs */ commandLineArgsForFile(src, perSrcFileFlags)));
      objectFiles.add(objectFile);
    }

    for (BuildRule dep : getDeps()) {
      // Only c++ static libraries are supported for now.
      if (dep instanceof CxxLibrary) {
        objectFiles.add(dep.getPathToOutputFile());
      }
    }

    steps.addAll(getFinalBuildSteps(objectFiles.build(), getPathToOutputFile()));

    return steps.build();
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder
        .set("compiler", getCompiler())
        .setSourcePaths("headers", headers);
  }

  @Override
  public Path getPathToOutputFile() {
    BuildTarget target = getBuildTarget();

    if (!target.getFlavors().contains(Flavor.DEFAULT)) {
      // TODO(grp): Consider putting this path format logic in BuildTargets.getBinPath() directly.
      return Paths.get(String.format("%s/%s/%s/" + getOutputFileNameFormat(),
              BuckConstant.BIN_DIR,
              target.getBasePathWithSlash(),
              target.getFlavorPostfix(),
              target.getShortNameOnly()));
    } else {
      return BuildTargets.getBinPath(target, getOutputFileNameFormat());
    }
  }
}
