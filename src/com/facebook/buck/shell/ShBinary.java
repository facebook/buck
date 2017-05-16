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
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MakeExecutableStep;
import com.facebook.buck.step.fs.StringTemplateStep;
import com.facebook.buck.util.Escaper;
import com.google.common.base.Joiner;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.stream.Stream;

public class ShBinary extends AbstractBuildRule implements BinaryBuildRule, HasRuntimeDeps {

  private static final Path TEMPLATE =
      Paths.get(
          System.getProperty(
              "buck.path_to_sh_binary_template", "src/com/facebook/buck/shell/sh_binary_template"));

  private final SourcePathRuleFinder ruleFinder;
  @AddToRuleKey private final SourcePath main;
  @AddToRuleKey private final ImmutableSet<SourcePath> resources;

  /** The path where the output will be written. */
  private final Path output;

  protected ShBinary(
      BuildRuleParams params,
      SourcePathRuleFinder ruleFinder,
      SourcePath main,
      ImmutableSet<SourcePath> resources) {
    super(params);
    this.ruleFinder = ruleFinder;
    this.main = main;
    this.resources = resources;

    BuildTarget target = params.getBuildTarget();
    this.output =
        BuildTargets.getGenPath(
            getProjectFilesystem(),
            target,
            String.format("__%%s__/%s.sh", target.getShortNameAndFlavorPostfix()));
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    buildableContext.recordArtifact(output);

    // Generate an .sh file that builds up an environment and invokes the user's script.
    // This generated .sh file will be returned by getExecutableCommand().
    // This script can be cached and used on machines other than the one where it was
    // created. That means it can't contain any absolute filepaths. Expose the absolute
    // filepath of the root of the project as $BUCK_REAL_ROOT, determined at runtime.
    int levelsBelowRoot = output.getNameCount() - 1;
    String pathBackToRoot = Joiner.on("/").join(Collections.nCopies(levelsBelowRoot, ".."));

    ImmutableList<String> resourceStrings =
        FluentIterable.from(resources)
            .transform(context.getSourcePathResolver()::getRelativePath)
            .transform(Object::toString)
            .transform(Escaper.BASH_ESCAPER)
            .toList();

    return new ImmutableList.Builder<Step>()
        .addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), output.getParent()))
        .add(
            new StringTemplateStep(
                TEMPLATE,
                getProjectFilesystem(),
                output,
                ImmutableMap.of(
                    "path_back_to_root",
                    pathBackToRoot,
                    "script_to_run",
                    Escaper.escapeAsBashString(
                        context.getSourcePathResolver().getRelativePath(main)),
                    "resources",
                    resourceStrings)))
        .add(new MakeExecutableStep(getProjectFilesystem(), output))
        .build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return new ExplicitBuildTargetSourcePath(getBuildTarget(), output);
  }

  @Override
  public Tool getExecutableCommand() {
    return new CommandTool.Builder()
        .addArg(SourcePathArg.of(new ExplicitBuildTargetSourcePath(getBuildTarget(), output)))
        .addInput(main)
        .addInputs(resources)
        .build();
  }

  // If the script is generated from another build rule, it needs to be available on disk
  // for this rule to be usable.
  @Override
  public Stream<BuildTarget> getRuntimeDeps() {
    return Stream.concat(resources.stream(), Stream.of(main))
        .map(ruleFinder::filterBuildRuleInputs)
        .flatMap(ImmutableSet::stream)
        .map(BuildRule::getBuildTarget);
  }
}
