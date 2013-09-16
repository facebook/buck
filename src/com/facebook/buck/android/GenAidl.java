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

package com.facebook.buck.android;

import static com.facebook.buck.rules.BuildableProperties.Kind.ANDROID;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.AbstractBuildable;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.RecordArtifactsInDirectoryStep;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.BuckConstant;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import javax.annotation.Nullable;

/**
 * Buildable for generating a .java file from an .aidl file. Example:
 * <pre>
 * # This will generate IOrcaService.java in the genfiles directory.
 * gen_aidl(
 *   name = 'orcaservice',
 *   aidl = 'IOrcaService.aidl',
 * )
 *
 * # The genfile() function flags the input as a file that can be found in the buck-out/gen
 * # directory.
 * android_library(
 *   name = 'server',
 *   srcs = glob(['*.java']) + [genfile('IOrcaService.java')],
 *   deps = [
 *     '//first-party/orca/lib-base:lib-base',
 *   ],
 * )
 * </pre>
 */
public class GenAidl extends AbstractBuildable {

  private final static BuildableProperties PROPERTIES = new BuildableProperties(ANDROID);

  private final BuildTarget buildTarget;
  private final String aidlFilePath;
  private final String importPath;

  private GenAidl(BuildTarget buildTarget,
      String aidlFilePath,
      String importPath) {
    this.buildTarget = Preconditions.checkNotNull(buildTarget);
    this.aidlFilePath = Preconditions.checkNotNull(aidlFilePath);
    this.importPath = Preconditions.checkNotNull(importPath);
  }

  @Override
  public BuildableProperties getProperties() {
    return PROPERTIES;
  }

  @Override
  @Nullable
  public String getPathToOutputFile() {
    // A gen_aidl() does not have a "primary output" at this time.
    return null;
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) throws IOException {
    // TODO(#2493457): This rule uses the aidl binary (part of the Android SDK), so the RuleKey
    // should incorporate which version of aidl is used.
    return builder
        .set("aidlFilePath", aidlFilePath)
        .set("importPath", importPath);
  }

  @Override
  public ImmutableList<String> getInputsToCompareToOutput() {
    return ImmutableList.of(aidlFilePath);
  }

  @Override
  public List<Step> getBuildSteps(BuildContext context, BuildableContext buildableContext)
      throws IOException {
    ImmutableList.Builder<Step> commands = ImmutableList.builder();

    Path outputDirectory = Paths.get(
        BuckConstant.BIN_DIR,
        buildTarget.getBasePath(),
        "." + buildTarget.getShortName() + ".aidl");
    commands.add(new MkdirStep(outputDirectory));

    AidlStep command = new AidlStep(aidlFilePath,
        ImmutableSet.of(importPath),
        outputDirectory);
    commands.add(command);

    // Files must ultimately be written to GEN_DIR to be used with genfile().
    String genDirectory = String.format("%s/%s", BuckConstant.GEN_DIR, importPath);
    commands.add(new MkdirStep(genDirectory));

    Function<String, Path> artifactPathTransform = new Function<String, Path>() {
      @Override
      public Path apply(String input) {
        return Paths.get(input);
      }
    };
    commands.add(new RecordArtifactsInDirectoryStep(
        buildableContext,
        outputDirectory,
        genDirectory,
        artifactPathTransform));

    return commands.build();
  }

  public static Builder newGenAidlRuleBuilder(AbstractBuildRuleBuilderParams params) {
    return new Builder(params);
  }

  public static class Builder extends AbstractBuildable.Builder {

    private String aidl;

    private String importPath;

    private Builder(AbstractBuildRuleBuilderParams params) {
      super(params);
    }

    @Override
    public BuildRuleType getType() {
      return BuildRuleType.GEN_AIDL;
    }

    @Override
    public GenAidl newBuildable(BuildRuleParams buildRuleParams,
        BuildRuleResolver ruleResolver) {
      return new GenAidl(buildRuleParams.getBuildTarget(), aidl, importPath);
    }

    @Override
    public Builder setBuildTarget(BuildTarget buildTarget) {
      super.setBuildTarget(buildTarget);
      return this;
    }

    public Builder setAidl(String aidl) {
      this.aidl = aidl;
      return this;
    }

    public Builder setImportPath(String importPath) {
      this.importPath = importPath;
      return this;
    }
  }
}
