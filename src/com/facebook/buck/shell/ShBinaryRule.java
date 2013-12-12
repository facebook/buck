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

package com.facebook.buck.shell;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractBuildRuleBuilder;
import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.DoNotUseAbstractBuildable;
import com.facebook.buck.rules.InitializableFromDisk;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.rules.ResourcesAttributeBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import javax.annotation.Nullable;

public class ShBinaryRule extends DoNotUseAbstractBuildable
    implements BinaryBuildRule, InitializableFromDisk<Object> {

  private final Path main;
  private final ImmutableSet<SourcePath> resources;

  /** The path where the output will be written. */
  private final Path output;

  // Dummy value
  @Nullable
  private Object buildOutput;

  protected ShBinaryRule(BuildRuleParams buildRuleParams,
      Path main,
      ImmutableSet<SourcePath> resources) {
    super(buildRuleParams);
    this.main = Preconditions.checkNotNull(main);
    this.resources = Preconditions.checkNotNull(resources);

    BuildTarget target = buildRuleParams.getBuildTarget();
    this.output = Paths.get(
        BuckConstant.GEN_DIR,
        target.getBasePath(),
        "__" + target.getShortName() + "__",
        target.getShortName() + ".sh");
  }

  @Override
  public Iterable<String> getInputsToCompareToOutput() {
    return Iterables.concat(ImmutableList.of(main.toString()),
        SourcePaths.filterInputsToCompareToOutput(resources));
  }

  @Override
  public List<Step> getBuildSteps(BuildContext context, BuildableContext buildableContext)
      throws IOException {
    MakeCleanDirectoryStep mkdir = new MakeCleanDirectoryStep(output.getParent().toString());

    // Generate an .sh file that builds up an environment and invokes the user's script.
    // This generated .sh file will be returned by getExecutableCommand().
    GenerateShellScriptStep generateShellScript = new GenerateShellScriptStep(
        Paths.get(getBuildTarget().getBasePath()),
        main,
        SourcePaths.toPaths(resources, context),
        output);

    return ImmutableList.of(mkdir, generateShellScript);
  }

  @Override
  public BuildRuleType getType() {
    return BuildRuleType.SH_BINARY;
  }

  @Override
  public String getPathToOutputFile() {
    return output.toString();
  }

  @Override
  public List<String> getExecutableCommand(ProjectFilesystem projectFilesystem) {
    return ImmutableList.of(projectFilesystem
          .getFileForRelativePath(output.toString())
          .getAbsolutePath()
          .toString());
  }

  public static Builder newShBinaryBuilder(AbstractBuildRuleBuilderParams params) {
    return new Builder(params);
  }

  /*
   * This method implements InitializableFromDisk so that it can make the output file
   * executable when this rule is populated from cache. The buildOutput Object is meaningless:
   * it is created only to satisfy InitializableFromDisk contract.
   * TODO (task #3321496) Delete this entire interface implementation after we fix zipping exe's.
   */
  @Override
  public Object initializeFromDisk(OnDiskBuildInfo info) {
    try {
      info.makeOutputFileExecutable(this);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return new Object();
  }

  @Override
  public void setBuildOutput(Object buildOutput) throws IllegalStateException {
    Preconditions.checkState(
        this.buildOutput == null,
        "buildOutput must not already be set for %s.",
        this);
    this.buildOutput = buildOutput;
  }

  @Override
  public Object getBuildOutput() throws IllegalStateException {
    Preconditions.checkState(buildOutput != null, "buildOutput must already be set for %s.", this);
    return buildOutput;
  }

  public static class Builder extends AbstractBuildRuleBuilder<ShBinaryRule>
      implements ResourcesAttributeBuilder {

    private Path main;
    private ImmutableSet.Builder<SourcePath> resources = ImmutableSet.builder();

    protected Builder(AbstractBuildRuleBuilderParams params) {
      super(params);
    }

    @Override
    public ShBinaryRule build(BuildRuleResolver ruleResolver) {
      BuildRuleParams params = createBuildRuleParams(ruleResolver);
      return new ShBinaryRule(params,
          main,
          resources.build());
    }

    public Builder setMain(Path main) {
      this.main = main;
      return this;
    }

    @Override
    public Builder addResource(SourcePath relativePathToResource) {
      resources.add(relativePathToResource);
      return this;
    }
  }
}
