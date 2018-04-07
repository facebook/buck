/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.features.lua;

import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.util.json.ObjectMappers;
import com.google.common.base.Functions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/** Builds a Lua executable into a standalone package using a given packager tool. */
public class LuaStandaloneBinary extends AbstractBuildRuleWithDeclaredAndExtraDeps {

  @AddToRuleKey private final Tool builder;

  @AddToRuleKey private final ImmutableList<String> builderArgs;

  @AddToRuleKey(stringify = true)
  private final Path output;

  @AddToRuleKey private final Optional<SourcePath> starter;

  @AddToRuleKey private final LuaPackageComponents components;

  @AddToRuleKey private final String mainModule;

  @AddToRuleKey private final Tool lua;

  private final boolean cache;

  public LuaStandaloneBinary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams,
      Tool builder,
      ImmutableList<String> builderArgs,
      Path output,
      Optional<SourcePath> starter,
      LuaPackageComponents components,
      String mainModule,
      Tool lua,
      boolean cache) {
    super(buildTarget, projectFilesystem, buildRuleParams);
    this.builder = builder;
    this.builderArgs = builderArgs;
    this.output = output;
    this.starter = starter;
    this.components = components;
    this.mainModule = mainModule;
    this.lua = lua;
    this.cache = cache;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {

    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    buildableContext.recordArtifact(output);

    // Make sure the parent directory exists.
    steps.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), output.getParent())));

    // Delete any other pex that was there (when switching between pex styles).
    steps.add(
        RmStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(), getProjectFilesystem(), output))
            .withRecursive(true));

    SourcePathResolver resolver = context.getSourcePathResolver();

    steps.add(
        new ShellStep(Optional.of(getBuildTarget()), getProjectFilesystem().getRootPath()) {

          @Override
          protected Optional<String> getStdin(ExecutionContext context) {
            try {
              return Optional.of(
                  ObjectMappers.WRITER.writeValueAsString(
                      ImmutableMap.of(
                          "modules",
                          Maps.transformValues(
                              components.getModules(),
                              Functions.compose(Object::toString, resolver::getAbsolutePath)),
                          "pythonModules",
                          Maps.transformValues(
                              components.getPythonModules(),
                              Functions.compose(Object::toString, resolver::getAbsolutePath)),
                          "nativeLibraries",
                          Maps.transformValues(
                              components.getNativeLibraries(),
                              Functions.compose(Object::toString, resolver::getAbsolutePath)))));
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          }

          @Override
          protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
            ImmutableList.Builder<String> command = ImmutableList.builder();
            command.addAll(builder.getCommandPrefix(resolver));
            command.addAll(builderArgs);
            command.add("--entry-point", mainModule);
            command.add("--interpreter");
            if (starter.isPresent()) {
              command.add(resolver.getAbsolutePath(starter.get()).toString());
            } else {
              command.add(lua.getCommandPrefix(resolver).get(0));
            }
            command.add(getProjectFilesystem().resolve(output).toString());
            return command.build();
          }

          @Override
          public String getShortName() {
            return "lua_package";
          }
        });

    return steps.build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), output);
  }

  @Override
  public boolean isCacheable() {
    return cache;
  }
}
