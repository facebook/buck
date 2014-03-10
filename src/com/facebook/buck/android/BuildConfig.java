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

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractBuildable;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.Buildable;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.util.BuckConstant;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

/**
 * {@link BuildConfig} is a {@link Buildable} that can generate a BuildConfig.java file.
 * <pre>
 * build_config(
 *   name = 'debug_build_config',
 *   package = 'com.example.package',
 *   debug = 'true',
 * )
 * </pre>
 * This will produce a genfile that will be parameterized by the name of the
 * {@code build_config} rule.
 * This can be used as a dependency for example
 * for an android library:
 * <pre>
 * android_library(
 *  name = 'my-app-lib',
 *  srcs = [genfile('__debug_build_config__/BuildConfig.java')] + glob(['src/**\/*.java']),
 *  deps = [
 *   ':debug_build_config',
 *  ],
 * )
 * </pre>
 */
public class BuildConfig extends AbstractBuildable {

  private final BuildTarget buildTarget;
  private final String appPackage;
  private final boolean debug;

  private final Path pathToOutputFile;

  protected BuildConfig(BuildTarget buildTarget,
      String appPackage, boolean debug) {
    this.buildTarget = Preconditions.checkNotNull(buildTarget);
    this.appPackage = appPackage;
    this.debug = debug;
    this.pathToOutputFile = Paths.get(
        BuckConstant.GEN_DIR,
        buildTarget.getBasePath(),
	"__" + buildTarget.getShortName() + "__",
	"BuildConfig.java");
  }

  @Override
  public Collection<Path> getInputsToCompareToOutput() {
    return ImmutableList.<Path>builder().build();
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) throws IOException {
    return builder;
  }

  public BuildTarget getBuildTarget() {
    return buildTarget;
  }

  public boolean getDebug() {
    return debug;
  }

  public String getAppPackage() {
    return appPackage;
  }

  @Override
  public List<Step> getBuildSteps(BuildContext context, BuildableContext buildableContext)
      throws IOException {
    ImmutableList.Builder<Step> commands = ImmutableList.builder();

    // Clear out the old file, if it exists.
    commands.add(new RmStep(pathToOutputFile,
        /* shouldForceDeletion */ true,
        /* shouldRecurse */ false));

    // Make sure the directory for the output file exists.
    commands.add(new MkdirStep(pathToOutputFile.getParent()));

    commands.add(new GenerateBuildConfigStep(
        getAppPackage(), getDebug(),
        getPathToOutputFile()));

    buildableContext.recordArtifact(pathToOutputFile);
    return commands.build();
  }

  @Override
  public Path getPathToOutputFile() {
    return pathToOutputFile;
  }

}
