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

package com.facebook.buck.cxx;

import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.FileScrubberStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;

public class CxxLink
    extends AbstractBuildRule
    implements SupportsInputBasedRuleKey {

  @AddToRuleKey
  private final Linker linker;
  @AddToRuleKey(stringify = true)
  private final Path output;
  @AddToRuleKey
  private final ImmutableList<Arg> args;

  public CxxLink(
      BuildRuleParams params,
      SourcePathResolver resolver,
      Linker linker,
      Path output,
      ImmutableList<Arg> args) {
    super(params, resolver);
    this.linker = linker;
    this.output = output;
    this.args = args;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    buildableContext.recordArtifact(output);
    Path scratchDir = BuildTargets.getScratchPath(getBuildTarget(), "%s-tmp");
    Path argFilePath = getProjectFilesystem().getRootPath().resolve(
        BuildTargets.getScratchPath(getBuildTarget(), "%s__argfile.txt"));

    // Try to find all the cell roots used during the link.  This isn't technically correct since,
    // in theory not all inputs need to come from build rules, but it probably works in practice.
    // One way that we know would work is exposing every known cell root paths, since the only rules
    // that we built (and therefore need to scrub) will be in one of those roots.
    ImmutableSet.Builder<Path> cellRoots = ImmutableSet.builder();
    for (BuildRule dep : getDeps()) {
      cellRoots.add(dep.getProjectFilesystem().getRootPath());
    }

    return ImmutableList.of(
        new MkdirStep(getProjectFilesystem(), output.getParent()),
        new MakeCleanDirectoryStep(getProjectFilesystem(), scratchDir),
        new CxxPrepareForLinkStep(
            argFilePath,
            output,
            Arg.stringify(args)),
        new CxxLinkStep(
            getProjectFilesystem().getRootPath(),
            linker.getEnvironment(getResolver()),
            linker.getCommandPrefix(getResolver()),
            argFilePath,
            getProjectFilesystem().getRootPath().resolve(scratchDir)),
        new FileScrubberStep(
            getProjectFilesystem(),
            output,
            linker.getScrubbers(cellRoots.build())));
  }

  @Override
  public Path getPathToOutput() {
    return output;
  }

  public Linker getLinker() {
    return linker;
  }

  public Path getOutput() {
    return output;
  }

  public ImmutableList<Arg> getArgs() {
    return args;
  }
}
