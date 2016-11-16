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

import com.facebook.buck.cxx.CxxBinary;
import com.facebook.buck.cxx.CxxLink;
import com.facebook.buck.cxx.ProvidesLinkedBinaryDeps;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.shell.DefaultShellStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.SortedSet;

/**
 * Puts together multiple thin library/binaries into a multi-arch file.
 */
public class MultiarchFile extends AbstractBuildRule implements ProvidesLinkedBinaryDeps {

  @AddToRuleKey
  private final Tool lipo;

  @AddToRuleKey
  private final ImmutableSortedSet<SourcePath> thinBinaries;

  @AddToRuleKey(stringify = true)
  private final Path output;

  public MultiarchFile(
      BuildRuleParams buildRuleParams,
      SourcePathResolver resolver,
      Tool lipo,
      SortedSet<SourcePath> thinBinaries,
      Path output) {
    super(buildRuleParams, resolver);
    this.lipo = lipo;
    this.thinBinaries = ImmutableSortedSet.copyOf(thinBinaries);
    this.output = output;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    buildableContext.recordArtifact(output);

    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    steps.add(new MkdirStep(getProjectFilesystem(), output.getParent()));

    lipoBinaries(steps);
    copyLinkerMapFiles(buildableContext, steps);

    return steps.build();
  }

  private void copyLinkerMapFiles(
      BuildableContext buildableContext,
      ImmutableList.Builder<Step> steps) {
    // Copy link maps.
    Path linkMapDir = Paths.get(output + "-LinkMap");
    steps.add(new MakeCleanDirectoryStep(getProjectFilesystem(), linkMapDir));

    for (SourcePath thinBinary : thinBinaries) {
      Optional<BuildRule> maybeRule = getResolver().getRule(thinBinary);
      if (maybeRule.isPresent()) {
        BuildRule rule = maybeRule.get();
        if (rule instanceof CxxBinary) {
          rule = ((CxxBinary) rule).getLinkRule();
        }
        if (rule instanceof CxxLink) {
          Optional<Path> maybeLinkerMapPath = ((CxxLink) rule).getLinkerMapPath();
          if (maybeLinkerMapPath.isPresent()) {
            Path source = maybeLinkerMapPath.get();
            Path dest = linkMapDir.resolve(source.getFileName());
            steps.add(CopyStep.forFile(getProjectFilesystem(), source, dest));
            buildableContext.recordArtifact(dest);
          }
        }
      }
    }
  }

  private void lipoBinaries(ImmutableList.Builder<Step> steps) {
    ImmutableList.Builder<String> commandBuilder = ImmutableList.builder();
    commandBuilder.addAll(lipo.getCommandPrefix(getResolver()));
    commandBuilder.add("-create", "-output", getProjectFilesystem().resolve(output).toString());
    for (SourcePath thinBinary : thinBinaries) {
      commandBuilder.add(getResolver().getAbsolutePath(thinBinary).toString());
    }
    steps.add(
        new DefaultShellStep(
            getProjectFilesystem().getRootPath(),
            commandBuilder.build(),
            lipo.getEnvironment(getResolver())));
  }

  @Override
  public Path getPathToOutput() {
    return output;
  }

  @Override
  public ImmutableSet<BuildRule> getStaticLibraryDeps() {
    ImmutableSet.Builder<BuildRule> builder = ImmutableSet.builder();
    for (BuildRule dep : getDeps()) {
      if (dep instanceof ProvidesLinkedBinaryDeps) {
        builder.addAll(((ProvidesLinkedBinaryDeps) dep).getStaticLibraryDeps());
      }
    }
    return builder.build();
  }

  @Override
  public ImmutableSet<BuildRule> getCompileDeps() {
    ImmutableSet.Builder<BuildRule> builder = ImmutableSet.builder();
    for (BuildRule dep : getDeps()) {
      if (dep instanceof ProvidesLinkedBinaryDeps) {
        builder.addAll(((ProvidesLinkedBinaryDeps) dep).getCompileDeps());
      }
    }
    return builder.build();
  }
}
