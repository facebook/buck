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
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.FileScrubberStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;

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
    Path argFilePath = getProjectFilesystem().getRootPath().resolve(
        BuildTargets.getScratchPath(getBuildTarget(), "%s__argfile.txt"));
    return ImmutableList.of(
        new MkdirStep(getProjectFilesystem(), output.getParent()),
        new CxxPrepareForLinkStep(
            argFilePath,
            output,
            FluentIterable.from(args)
                .transform(Arg.stringifyFunction())
                .toList()),
        new CxxLinkStep(
            getProjectFilesystem().getRootPath(),
            linker.getCommandPrefix(getResolver()),
            argFilePath),
        new FileScrubberStep(
            getProjectFilesystem(),
            output,
            linker.getScrubbers(getProjectFilesystem().getRootPath())));
  }

  @Override
  public Path getPathToOutput() {
    return output;
  }

  public Tool getLinker() {
    return linker;
  }

  public Path getOutput() {
    return output;
  }

  @VisibleForTesting
  protected ImmutableList<String> getArgs() {
    return FluentIterable.from(args)
        .transform(Arg.stringifyFunction())
        .toList();
  }
}
