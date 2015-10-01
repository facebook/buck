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

import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.shell.DefaultShellStep;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.SortedSet;

/**
 * Puts together multiple thin binaries into a fat binary.
 */
public class FatBinary extends AbstractBuildRule {

  @AddToRuleKey
  private final Tool lipo;

  @AddToRuleKey
  private final ImmutableSortedSet<SourcePath> thinBinaries;

  @AddToRuleKey(stringify = true)
  private final Path output;

  public FatBinary(
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
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<String> commandBuilder = ImmutableList.builder();
    commandBuilder.addAll(lipo.getCommandPrefix(getResolver()));
    commandBuilder.add("-create", "-output", getProjectFilesystem().resolve(output).toString());
    for (SourcePath thinBinary : thinBinaries) {
      commandBuilder.add(getResolver().getResolvedPath(thinBinary).toString());
    }
    return ImmutableList.<Step>of(
        new DefaultShellStep(getProjectFilesystem().getRootPath(), commandBuilder.build()));
  }

  @Override
  public Path getPathToOutput() {
    return output;
  }
}
