/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.cxx;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.DefaultFieldDeps;
import com.facebook.buck.core.rulekey.DefaultFieldInputs;
import com.facebook.buck.core.rulekey.DefaultFieldSerialization;
import com.facebook.buck.core.rulekey.ExcludeFromRuleKey;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;

/** Rule which creates a scrubbed dylib stub from a dylib. */
public class MachoDylibStubRule extends ModernBuildRule<MachoDylibStubRule.DylibStubBuildable> {
  private MachoDylibStubRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      DylibStubBuildable buildable) {
    super(buildTarget, projectFilesystem, ruleFinder, buildable);
  }

  /** Creates the rule for the input dylib. */
  public static MachoDylibStubRule from(
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      Tool strip,
      SourcePath inputDylib) {
    String libName =
        ruleFinder.getSourcePathResolver().getRelativePath(inputDylib).getFileName().toString();
    return new MachoDylibStubRule(
        target,
        projectFilesystem,
        ruleFinder,
        new DylibStubBuildable(target, strip, libName, inputDylib));
  }

  /** The buildable for the rule */
  static class DylibStubBuildable implements Buildable {
    @AddToRuleKey protected final BuildTarget buildTarget;
    @AddToRuleKey private final Tool strip;
    @AddToRuleKey private final OutputPath outputPath;
    @AddToRuleKey private final String libName;

    // The main idea behind the dylib stub rule is to prevent unnecessary relinking of
    // any dependents of the dylib if and only if the ABI does not change. But any changes to
    // symbol implementations ends up changing the actual dylib. That's why we want to generate
    // a dylib stub which represents the original dylib ABI but its independent of the symbol
    // implementations. Below, we exclude the original dylib from the rulekey calculation
    // which allows us to skip re-linking any dependents, as the dylib stub's rulekey would not
    // change if the ABI stays the same.
    @ExcludeFromRuleKey(
        reason = "ABI can stay the same even if input dylib changes",
        serialization = DefaultFieldSerialization.class,
        inputs = DefaultFieldInputs.class,
        deps = DefaultFieldDeps.class)
    private final SourcePath inputDylib;

    private DylibStubBuildable(
        BuildTarget buildTarget, Tool strip, String libName, SourcePath inputDylib) {
      this.buildTarget = buildTarget;
      this.strip = strip;
      this.libName = libName;
      this.inputDylib = inputDylib;
      this.outputPath = new OutputPath(libName);
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {

      Path output = outputPathResolver.resolvePath(outputPath);
      ImmutableList.Builder<Step> steps = ImmutableList.builder();

      SourcePathResolverAdapter sourcePathResolverAdapter = buildContext.getSourcePathResolver();
      ImmutableList<String> stripToolPrefix = strip.getCommandPrefix(sourcePathResolverAdapter);

      steps.add(
          new MachoScrubContentSectionsStep(
              stripToolPrefix,
              sourcePathResolverAdapter.getFilesystem(inputDylib),
              sourcePathResolverAdapter.getRelativePath(inputDylib),
              filesystem,
              output),
          new MachoDylibStubScrubContentsStep(filesystem, output));

      return steps.build();
    }
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    DylibStubBuildable buildable = getBuildable();
    return getSourcePath(buildable.outputPath);
  }
}
