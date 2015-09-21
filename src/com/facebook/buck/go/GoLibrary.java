/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.go;

import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;

public class GoLibrary extends GoLinkable {
  @AddToRuleKey
  private final Tool compiler;
  @AddToRuleKey(stringify = true)
  private final Path packageName;
  @AddToRuleKey
  private final ImmutableSet<SourcePath> srcs;
  @AddToRuleKey
  private final ImmutableList<String> flags;

  private final GoSymlinkTree symlinkTree;
  private final Path output;

  public GoLibrary(
      BuildRuleParams params,
      SourcePathResolver resolver,
      GoSymlinkTree symlinkTree,
      Path packageName,
      ImmutableSet<SourcePath> srcs,
      ImmutableList<String> compilerFlags,
      Tool compiler) {
    super(params, resolver);
    this.srcs = srcs;
    this.symlinkTree = symlinkTree;
    this.packageName = packageName;
    this.flags = compilerFlags;
    this.compiler = compiler;
    this.output = BuildTargets.getGenPath(
        getBuildTarget(), "%s/" + getBuildTarget().getShortName() + ".a");
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    ImmutableList.Builder<Path> compileSrcList = ImmutableList.builder();
    for (SourcePath path : srcs) {
      Path outputPath = getResolver().getPath(path);
      compileSrcList.add(outputPath);
    }

    return ImmutableList.of(
        new MkdirStep(getProjectFilesystem(), output.getParent()),
        new GoCompileStep(
            getProjectFilesystem().getRootPath(),
            compiler.getCommandPrefix(getResolver()),
            flags,
            packageName,
            compileSrcList.build(),
            ImmutableList.<Path>of(symlinkTree.getRoot()),
            output));
  }

  @Override
  public Path getGoPackageName() {
    return packageName;
  }

  @Override
  public Path getPathToOutput() {
    return output;
  }

  @Override
  public BuildableProperties getProperties() {
    return new BuildableProperties(BuildableProperties.Kind.LIBRARY);
  }
}
