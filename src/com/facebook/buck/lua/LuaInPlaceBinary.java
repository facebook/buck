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

package com.facebook.buck.lua;

import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.WriteFileStep;
import com.facebook.buck.util.Escaper;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.io.Resources;

import org.stringtemplate.v4.ST;

import java.io.IOException;
import java.nio.file.Path;

public class LuaInPlaceBinary extends LuaBinary {

  private static final String RUN_INPLACE_RESOURCE = "com/facebook/buck/lua/run_inplace.lua.in";

  // TODO(andrewjcg): Task #8098647: This rule has no steps, so it
  // really doesn't need a rule key.
  //
  // However, Lua tests will never be re-run if the rule key
  // doesn't change, so we use the rule key to force the test runner
  // to re-run the tests if the input changes.
  //
  // We should upate the Lua test rule to account for this.
  @AddToRuleKey
  private final Tool lua;
  @AddToRuleKey
  private final LuaPackageComponents components;
  private final SymlinkTree linkTree;
  @AddToRuleKey
  private final Supplier<String> script;

  public LuaInPlaceBinary(
      BuildRuleParams params,
      SourcePathResolver resolver,
      Tool lua,
      String mainModule,
      LuaPackageComponents components,
      SymlinkTree linkTree,
      String extension) {
    super(params, resolver, lua, mainModule, components, extension);
    this.lua = lua;
    this.components = components;
    this.linkTree = linkTree;
    this.script =
        getScript(
            resolver,
            lua,
            mainModule,
            getProjectFilesystem()
                .resolve(getBinPath())
                .getParent()
                .relativize(linkTree.getRoot()));
  }

  private static String getRunInplaceResource() {
    try {
      return Resources.toString(Resources.getResource(RUN_INPLACE_RESOURCE), Charsets.UTF_8);
    } catch (IOException e) {
      throw Throwables.propagate(e);
    }
  }

  private static Supplier<String> getScript(
      final SourcePathResolver pathResolver,
      final Tool lua,
      final String mainModule,
      final Path relativeLinkTreeRoot) {
    final String relativeLinkTreeRootStr =
        Escaper.escapeAsPythonString(relativeLinkTreeRoot.toString());
    return new Supplier<String>() {
      @Override
      public String get() {
        ImmutableList<String> luaCommand = lua.getCommandPrefix(pathResolver);
        Preconditions.checkArgument(luaCommand.size() == 1);
        return new ST(getRunInplaceResource())
            .add("SHEBANG", luaCommand.get(0))
            .add("LUA", Escaper.escapeAsPythonString(luaCommand.get(0)))
            .add("MAIN_MODULE", Escaper.escapeAsPythonString(mainModule))
            .add("MODULES_DIR", relativeLinkTreeRootStr)
            .render();
      }
    };
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    Path binPath = getBinPath();
    buildableContext.recordArtifact(binPath);
    return ImmutableList.<Step>of(
        new WriteFileStep(getProjectFilesystem(), script, binPath, /* executable */ true));
  }

  @Override
  public Tool getExecutableCommand() {
    return new CommandTool.Builder(lua)
        .addArg(new BuildTargetSourcePath(getBuildTarget()))
        .addDep(linkTree)
        .addInputs(components.getModules().values())
        .addInputs(components.getNativeLibraries().values())
        .build();
  }

  @Override
  public ImmutableSortedSet<BuildRule> getRuntimeDeps() {
    return ImmutableSortedSet.<BuildRule>naturalOrder()
        .add(linkTree)
        .addAll(getResolver().filterBuildRuleInputs(components.getModules().values()))
        .addAll(getResolver().filterBuildRuleInputs(components.getNativeLibraries().values()))
        .addAll(super.getRuntimeDeps())
        .build();
  }

}
