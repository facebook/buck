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

package com.facebook.buck.features.python;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.rules.impl.SymlinkTree;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.features.python.PythonBuckConfig.PackageStyle;
import com.facebook.buck.features.python.toolchain.PythonPlatform;
import com.facebook.buck.io.filesystem.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.isolatedsteps.common.WriteFileIsolatedStep;
import com.facebook.buck.test.selectors.Nullable;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.stream.RichStream;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.io.Resources;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.SortedSet;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.stringtemplate.v4.ST;
import org.stringtemplate.v4.STGroup;

public class PythonInPlaceBinary extends PythonBinary implements HasRuntimeDeps {

  private static final String RUN_INPLACE_RESOURCE = "run_inplace.py.in";
  private static final String RUN_INPLACE_INTERPRETER_WRAPPER_RESOURCE =
      "run_inplace_interpreter_wrapper.py.in";
  private static final String RUN_INPLACE_LITE_RESOURCE = "run_inplace_lite.py.in";

  // TODO(agallagher): Task #8098647: This rule has no steps, so it
  // really doesn't need a rule key.
  //
  // However, Python tests will never be re-run if the rule key
  // doesn't change, so we use the rule key to force the test runner
  // to re-run the tests if the input changes.
  //
  // We should upate the Python test rule to account for this.
  private final SymlinkTree linkTree;
  private final RelPath interpreterWrapperGenPath;
  @AddToRuleKey private final Tool python;
  @AddToRuleKey private final Supplier<String> binScript;
  @AddToRuleKey private final Supplier<String> interpreterWrapperScript;

  PythonInPlaceBinary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver ruleResolver,
      Supplier<? extends SortedSet<BuildRule>> originalDeclareDeps,
      CxxPlatform cxxPlatform,
      PythonPlatform pythonPlatform,
      String mainModule,
      PythonPackageComponents components,
      String pexExtension,
      ImmutableSet<String> preloadLibraries,
      boolean legacyOutputPath,
      SymlinkTree linkTree,
      Tool python,
      PackageStyle packageStyle) {
    super(
        buildTarget,
        projectFilesystem,
        originalDeclareDeps,
        pythonPlatform,
        mainModule,
        components,
        preloadLibraries,
        pexExtension,
        legacyOutputPath);
    this.linkTree = linkTree;
    this.python = python;
    this.interpreterWrapperGenPath =
        getInterpreterWrapperGenPath(
            buildTarget, projectFilesystem, pexExtension, legacyOutputPath);
    AbsPath targetRoot =
        projectFilesystem
            .resolve(getBinPath(buildTarget, projectFilesystem, pexExtension, legacyOutputPath))
            .getParent();
    this.binScript =
        getBinScript(
            pythonPlatform,
            mainModule,
            targetRoot.relativize(linkTree.getRoot()),
            targetRoot.relativize(projectFilesystem.resolve(interpreterWrapperGenPath)),
            packageStyle);
    this.interpreterWrapperScript =
        getInterpreterWrapperScript(
            ruleResolver,
            buildTarget.getTargetConfiguration(),
            pythonPlatform,
            cxxPlatform,
            components,
            targetRoot.relativize(linkTree.getRoot()),
            preloadLibraries,
            packageStyle);
  }

  @Override
  public boolean outputFileCanBeCopied() {
    return true;
  }

  private static String getRunInplaceResource() {
    return getNamedResource(RUN_INPLACE_RESOURCE);
  }

  private static String getRunInplaceInterpreterWrapperResource() {
    return getNamedResource(RUN_INPLACE_INTERPRETER_WRAPPER_RESOURCE);
  }

  private static String getRunInplaceLiteResource() {
    return getNamedResource(RUN_INPLACE_LITE_RESOURCE);
  }

  private static String getNamedResource(String resourceName) {
    try {
      return Resources.toString(
          Resources.getResource(PythonInPlaceBinary.class, resourceName), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static RelPath getInterpreterWrapperGenPath(
      BuildTarget target,
      ProjectFilesystem filesystem,
      String extension,
      boolean legacyOutputPath) {
    if (!legacyOutputPath) {
      target = target.withFlavors();
    }
    return BuildTargetPaths.getGenPath(
        filesystem.getBuckPaths(), target, "%s#interpreter_wrapper" + extension);
  }

  private static Supplier<String> getBinScript(
      PythonPlatform pythonPlatform,
      String mainModule,
      RelPath linkTreeRoot,
      RelPath interpreterWrapperPath,
      PackageStyle packageStyle) {
    return () -> {
      String linkTreeRootStr = Escaper.escapeAsPythonString(linkTreeRoot.toString());
      String interpreterWrapperPathStr =
          Escaper.escapeAsPythonString(interpreterWrapperPath.toString());
      return new ST(
              new STGroup(),
              packageStyle == PackageStyle.INPLACE
                  ? getRunInplaceResource()
                  : getRunInplaceLiteResource())
          .add("PYTHON", pythonPlatform.getEnvironment().getPythonPath())
          .add("PYTHON_INTERPRETER_FLAGS", pythonPlatform.getInplaceBinaryInterpreterFlags())
          .add("MODULES_DIR", linkTreeRootStr)
          .add("MAIN_MODULE", Escaper.escapeAsPythonString(mainModule))
          .add("INTERPRETER_WRAPPER_REL_PATH", interpreterWrapperPathStr)
          .render();
    };
  }

  @Nullable
  private static Supplier<String> getInterpreterWrapperScript(
      BuildRuleResolver resolver,
      TargetConfiguration targetConfiguration,
      PythonPlatform pythonPlatform,
      CxxPlatform cxxPlatform,
      PythonPackageComponents components,
      RelPath relativeLinkTreeRoot,
      ImmutableSet<String> preloadLibraries,
      PackageStyle packageStyle) {
    String relativeLinkTreeRootStr = Escaper.escapeAsPythonString(relativeLinkTreeRoot.toString());
    Linker ld = cxxPlatform.getLd().resolve(resolver, targetConfiguration);
    // Lite mode doesn't need an interpreter wrapper as there's no LD_PRELOADs involved.
    if (packageStyle != PackageStyle.INPLACE) {
      return null;
    }
    return () -> {
      ST st =
          new ST(new STGroup(), getRunInplaceInterpreterWrapperResource())
              .add("PYTHON", pythonPlatform.getEnvironment().getPythonPath())
              .add("PYTHON_INTERPRETER_FLAGS", pythonPlatform.getInplaceBinaryInterpreterFlags())
              .add("MODULES_DIR", relativeLinkTreeRootStr);

      // Only add platform-specific values when the binary includes native libraries.
      if (components.getNativeLibraries().getComponents().isEmpty()) {
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
    RelPath binPath = context.getSourcePathResolver().getCellUnsafeRelPath(getSourcePathToOutput());
    buildableContext.recordArtifact(binPath.getPath());
    ImmutableList.Builder<Step> stepsBuilder = new ImmutableList.Builder<Step>();
    stepsBuilder
        .add(
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(), getProjectFilesystem(), binPath.getParent())))
        .add(WriteFileIsolatedStep.of(binScript, binPath, /* executable */ true));

    if (interpreterWrapperScript != null) {
      RelPath interpreterWrapperPath =
          context
              .getSourcePathResolver()
              .getCellUnsafeRelPath(
                  ExplicitBuildTargetSourcePath.of(getBuildTarget(), interpreterWrapperGenPath));
      buildableContext.recordArtifact(interpreterWrapperPath.getPath());
      stepsBuilder.add(
          WriteFileIsolatedStep.of(
              interpreterWrapperScript, interpreterWrapperPath, /* executable */ true));
    }
    return stepsBuilder.build();
  }

  @Override
  public Tool getExecutableCommand(OutputLabel outputLabel) {
    CommandTool.Builder builder = new CommandTool.Builder(python);
    getPythonPlatform().getInplaceBinaryInterpreterFlags().forEach(builder::addArg);
    getComponents().forEachInput(builder::addInput);
    return builder
        .addArg(SourcePathArg.of(getSourcePathToOutput()))
        .addNonHashableInput(linkTree.getRootSourcePath())
        .build();
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps(BuildRuleResolver buildRuleResolver) {
    return RichStream.from(super.getRuntimeDeps(buildRuleResolver))
        .concat(Stream.of(linkTree.getBuildTarget()))
        .concat(getComponents().getDeps(buildRuleResolver).stream().map(BuildRule::getBuildTarget));
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    // The actual steps of a in-place binary doesn't actually have any build-time deps.
    return ImmutableSortedSet.of();
  }

  SymlinkTree getLinkTree() {
    return linkTree;
  }

  @Override
  public boolean supportsHashedBuckOutHardLinking() {
    return false;
  }
}
