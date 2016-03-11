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

package com.facebook.buck.python;

import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.facebook.buck.util.Escaper;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Supplier;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.io.Resources;

import org.stringtemplate.v4.ST;

import java.io.IOException;
import java.nio.file.Path;

public class PythonInPlaceBinary extends PythonBinary implements HasRuntimeDeps {

  private static final String RUN_INPLACE_RESOURCE = "com/facebook/buck/python/run_inplace.py.in";

  // TODO(andrewjcg): Task #8098647: This rule has no steps, so it
  // really doesn't need a rule key.
  //
  // However, Python tests will never be re-run if the rule key
  // doesn't change, so we use the rule key to force the test runner
  // to re-run the tests if the input changes.
  //
  // We should upate the Python test rule to account for this.
  @AddToRuleKey
  private final Supplier<String> script;
  private final SymlinkTree linkTree;
  @AddToRuleKey
  private final PythonPackageComponents components;
  @AddToRuleKey
  private final Tool python;

  public PythonInPlaceBinary(
      BuildRuleParams params,
      SourcePathResolver resolver,
      BuildRuleResolver ruleResolver,
      PythonPlatform pythonPlatform,
      CxxPlatform cxxPlatform,
      SymlinkTree linkTree,
      String mainModule,
      PythonPackageComponents components,
      Tool python,
      String pexExtension,
      ImmutableSet<String> preloadLibraries) {
    super(params, resolver, pythonPlatform, mainModule, components, preloadLibraries, pexExtension);
    this.script =
        getScript(
            ruleResolver,
            pythonPlatform,
            cxxPlatform,
            mainModule,
            components,
            getProjectFilesystem()
                .resolve(getBinPath())
                .getParent()
                .relativize(linkTree.getRoot()),
            preloadLibraries);
    this.linkTree = linkTree;
    this.components = components;
    this.python = python;
  }

  private static String getRunInplaceResource() {
    try {
      return Resources.toString(Resources.getResource(RUN_INPLACE_RESOURCE), Charsets.UTF_8);
    } catch (IOException e) {
      throw Throwables.propagate(e);
    }
  }

  private static Supplier<String> getScript(
      final BuildRuleResolver resolver,
      final PythonPlatform pythonPlatform,
      final CxxPlatform cxxPlatform,
      final String mainModule,
      final PythonPackageComponents components,
      final Path relativeLinkTreeRoot,
      final ImmutableSet<String> preloadLibraries) {
    final String relativeLinkTreeRootStr =
        Escaper.escapeAsPythonString(relativeLinkTreeRoot.toString());
    final Linker ld = cxxPlatform.getLd().resolve(resolver);
    return new Supplier<String>() {
      @Override
      public String get() {
        return new ST(getRunInplaceResource())
            .add("PYTHON", pythonPlatform.getEnvironment().getPythonPath())
            .add("MAIN_MODULE", Escaper.escapeAsPythonString(mainModule))
            .add("MODULES_DIR", relativeLinkTreeRootStr)
            .add(
                "NATIVE_LIBS_ENV_VAR",
                Escaper.escapeAsPythonString(ld.searchPathEnvVar()))
            .add(
                "NATIVE_LIBS_DIR",
                components.getNativeLibraries().isEmpty() ?
                    "None" :
                    relativeLinkTreeRootStr)
            .add(
                "NATIVE_LIBS_PRELOAD_ENV_VAR",
                Escaper.escapeAsPythonString(ld.preloadEnvVar()))
            .add(
                "NATIVE_LIBS_PRELOAD",
                Escaper.escapeAsPythonString(Joiner.on(':').join(preloadLibraries)))
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
    return ImmutableList.of(
        new MkdirStep(getProjectFilesystem(), binPath.getParent()),
        new WriteFileStep(getProjectFilesystem(), script, binPath, /* executable */ true));
  }

  @Override
  public Tool getExecutableCommand() {
    return new CommandTool.Builder(python)
        .addArg(new SourcePathArg(getResolver(), new BuildTargetSourcePath(getBuildTarget())))
        .addDep(linkTree)
        .addInputs(components.getModules().values())
        .addInputs(components.getResources().values())
        .addInputs(components.getNativeLibraries().values())
        .build();
  }

  @Override
  public ImmutableSortedSet<BuildRule> getRuntimeDeps() {
    return ImmutableSortedSet.<BuildRule>naturalOrder()
        .add(linkTree)
        .addAll(getResolver().filterBuildRuleInputs(components.getModules().values()))
        .addAll(getResolver().filterBuildRuleInputs(components.getResources().values()))
        .addAll(getResolver().filterBuildRuleInputs(components.getNativeLibraries().values()))
        .build();
  }

}
