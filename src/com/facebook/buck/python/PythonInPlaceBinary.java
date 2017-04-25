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
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.RichStream;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.io.Resources;
import java.io.IOException;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.stringtemplate.v4.ST;

public class PythonInPlaceBinary extends PythonBinary implements HasRuntimeDeps {

  private static final String RUN_INPLACE_RESOURCE = "com/facebook/buck/python/run_inplace.py.in";

  // TODO(agallagher): Task #8098647: This rule has no steps, so it
  // really doesn't need a rule key.
  //
  // However, Python tests will never be re-run if the rule key
  // doesn't change, so we use the rule key to force the test runner
  // to re-run the tests if the input changes.
  //
  // We should upate the Python test rule to account for this.
  private final SourcePathRuleFinder ruleFinder;
  private final SymlinkTree linkTree;
  @AddToRuleKey private final Tool python;
  @AddToRuleKey private final Supplier<String> script;

  private PythonInPlaceBinary(
      BuildRuleParams params,
      Supplier<ImmutableSortedSet<BuildRule>> originalDeclareDeps,
      PythonPlatform pythonPlatform,
      String mainModule,
      PythonPackageComponents components,
      String pexExtension,
      ImmutableSet<String> preloadLibraries,
      boolean legacyOutputPath,
      SourcePathRuleFinder ruleFinder,
      SymlinkTree linkTree,
      Tool python,
      Supplier<String> script) {
    super(
        params,
        originalDeclareDeps,
        pythonPlatform,
        mainModule,
        components,
        preloadLibraries,
        pexExtension,
        legacyOutputPath);
    this.ruleFinder = ruleFinder;
    this.linkTree = linkTree;
    this.python = python;
    this.script = script;
  }

  public static PythonInPlaceBinary from(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      CxxPlatform cxxPlatform,
      PythonPlatform pythonPlatform,
      String mainModule,
      PythonPackageComponents components,
      String pexExtension,
      ImmutableSet<String> preloadLibraries,
      boolean legacyOutputPath,
      SourcePathRuleFinder ruleFinder,
      SymlinkTree linkTree,
      Tool python) {
    return new PythonInPlaceBinary(
        // The actual steps of a in-place binary doesn't actually have any build-time deps.
        params.copyReplacingDeclaredAndExtraDeps(
            Suppliers.ofInstance(ImmutableSortedSet.of()),
            Suppliers.ofInstance(ImmutableSortedSet.of())),
        params.getDeclaredDeps(),
        pythonPlatform,
        mainModule,
        components,
        pexExtension,
        preloadLibraries,
        legacyOutputPath,
        ruleFinder,
        linkTree,
        python,
        getScript(
            ruleResolver,
            pythonPlatform,
            cxxPlatform,
            mainModule,
            components,
            params
                .getProjectFilesystem()
                .resolve(
                    getBinPath(
                        params.getBuildTarget(),
                        params.getProjectFilesystem(),
                        pexExtension,
                        legacyOutputPath))
                .getParent()
                .relativize(linkTree.getRoot()),
            preloadLibraries));
  }

  @Override
  public boolean outputFileCanBeCopied() {
    return true;
  }

  private static String getRunInplaceResource() {
    try {
      return Resources.toString(Resources.getResource(RUN_INPLACE_RESOURCE), Charsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException(e);
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
    return () -> {
      ST st =
          new ST(getRunInplaceResource())
              .add("PYTHON", pythonPlatform.getEnvironment().getPythonPath())
              .add("MAIN_MODULE", Escaper.escapeAsPythonString(mainModule))
              .add("MODULES_DIR", relativeLinkTreeRootStr);

      // Only add platform-specific values when the binary includes native libraries.
      if (components.getNativeLibraries().isEmpty()) {
        st.add("NATIVE_LIBS_ENV_VAR", "None");
        st.add("NATIVE_LIBS_DIR", "None");
      } else {
        st.add("NATIVE_LIBS_ENV_VAR", Escaper.escapeAsPythonString(ld.searchPathEnvVar()));
        st.add("NATIVE_LIBS_DIR", relativeLinkTreeRootStr);
      }

      if (preloadLibraries.isEmpty()) {
        st.add("NATIVE_LIBS_PRELOAD_ENV_VAR", "None");
        st.add("NATIVE_LIBS_PRELOAD", "None");
      } else {
        st.add("NATIVE_LIBS_PRELOAD_ENV_VAR", Escaper.escapeAsPythonString(ld.preloadEnvVar()));
        st.add(
            "NATIVE_LIBS_PRELOAD",
            Escaper.escapeAsPythonString(Joiner.on(':').join(preloadLibraries)));
      }
      return st.render();
    };
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    Path binPath = context.getSourcePathResolver().getRelativePath(getSourcePathToOutput());
    buildableContext.recordArtifact(binPath);
    return ImmutableList.of(
        MkdirStep.of(getProjectFilesystem(), binPath.getParent()),
        new WriteFileStep(getProjectFilesystem(), script, binPath, /* executable */ true));
  }

  @Override
  public Tool getExecutableCommand() {
    return new CommandTool.Builder(python)
        .addArg(SourcePathArg.of(getSourcePathToOutput()))
        .addDep(linkTree)
        .addInputs(getComponents().getModules().values())
        .addInputs(getComponents().getResources().values())
        .addInputs(getComponents().getNativeLibraries().values())
        .build();
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps() {
    return RichStream.<BuildTarget>empty()
        .concat(super.getRuntimeDeps())
        .concat(Stream.of(linkTree.getBuildTarget()))
        .concat(getComponents().getDeps(ruleFinder).stream().map(BuildRule::getBuildTarget));
  }
}
