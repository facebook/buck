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

package com.facebook.buck.android;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildable;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.Buildable;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;

/**
 * {@link BuildConfig} is a {@link Buildable} that can generate a BuildConfig.java file.
 * <pre>
 * build_config(
 *   name = 'debug_build_config',
 *   package = 'com.example.package',
 *   debug = True,
 * )
 * </pre>
 * This will produce a genfile that will be parameterized by the name of the
 * {@code build_config} rule.
 * This can be used as a dependency for example
 * for an android library:
 * <pre>
 * android_library(
 *   name = 'my-app-lib',
 *   srcs = [':debug_build_config'] + glob(['src/**&#47;*.java']),
 *   deps = [
 *     ':debug_build_config',
 *   ],
 * )
 * </pre>
 */
public class BuildConfig extends AbstractBuildable {

  private final String appPackage;
  private final boolean debug;
  private final Path pathToOutputFile;

  protected BuildConfig(
      BuildTarget buildTarget,
      String appPackage,
      boolean debug) {
    super(buildTarget);
    this.appPackage = Preconditions.checkNotNull(appPackage);
    this.debug = debug;
    this.pathToOutputFile = BuildTargets.getGenPath(buildTarget, "__%s__")
        .resolve("BuildConfig.java");
  }

  @Override
  public ImmutableCollection<Path> getInputsToCompareToOutput() {
    return ImmutableList.of();
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder
        .set("package", appPackage)
        .set("debug", debug);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {

    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    steps.add(new MakeCleanDirectoryStep(pathToOutputFile.getParent()));
    steps.add(new GenerateBuildConfigStep(appPackage, debug, pathToOutputFile));

    buildableContext.recordArtifact(pathToOutputFile);
    return steps.build();
  }

  @Override
  public Path getPathToOutputFile() {
    return pathToOutputFile;
  }

}
