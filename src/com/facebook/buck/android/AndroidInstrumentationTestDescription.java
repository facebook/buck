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

package com.facebook.buck.android;

import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.description.arg.HasTestTimeout;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.common.BuildRules;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.java.JavaOptions;
import com.facebook.buck.jvm.java.toolchain.JavaOptionsProvider;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.macros.ClasspathMacroExpander;
import com.facebook.buck.rules.macros.ExecutableMacro;
import com.facebook.buck.rules.macros.ExecutableMacroExpander;
import com.facebook.buck.rules.macros.ExecutableTargetMacro;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.Macro;
import com.facebook.buck.rules.macros.MacroExpander;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.macros.StringWithMacrosConverter;
import com.facebook.buck.test.config.TestBuckConfig;
import com.google.common.collect.ImmutableCollection.Builder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class AndroidInstrumentationTestDescription
    implements DescriptionWithTargetGraph<AndroidInstrumentationTestDescriptionArg>,
        ImplicitDepsInferringDescription<AndroidInstrumentationTestDescriptionArg> {

  private static final ImmutableList<MacroExpander<? extends Macro, ?>> MACRO_EXPANDERS =
      ImmutableList.of(
          LocationMacroExpander.INSTANCE,
          new ClasspathMacroExpander(),
          new ExecutableMacroExpander<>(ExecutableMacro.class),
          new ExecutableMacroExpander<>(ExecutableTargetMacro.class));

  private final TestBuckConfig testBuckConfig;
  private final DownwardApiConfig downwardApiConfig;
  private final ConcurrentHashMap<ProjectFilesystem, ConcurrentHashMap<String, PackagedResource>>
      resourceSupplierCache;
  private final Function<TargetConfiguration, JavaOptions> javaOptionsForTests;

  public AndroidInstrumentationTestDescription(
      TestBuckConfig testBuckConfig,
      DownwardApiConfig downwardApiConfig,
      ToolchainProvider toolchainProvider) {
    this.testBuckConfig = testBuckConfig;
    this.downwardApiConfig = downwardApiConfig;
    this.javaOptionsForTests = JavaOptionsProvider.getDefaultJavaOptionsForTests(toolchainProvider);
    this.resourceSupplierCache = new ConcurrentHashMap<>();
  }

  @Override
  public Class<AndroidInstrumentationTestDescriptionArg> getConstructorArgType() {
    return AndroidInstrumentationTestDescriptionArg.class;
  }

  @Override
  public AndroidInstrumentationTest createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      AndroidInstrumentationTestDescriptionArg args) {
    BuildRule apk = context.getActionGraphBuilder().getRule(args.getApk());
    if (!(apk instanceof HasInstallableApk)) {
      throw new HumanReadableException(
          "In %s, instrumentation_apk='%s' must be an android_binary(), apk_genrule() or "
              + "android_instrumentation_apk(), but was %s().",
          buildTarget, apk.getFullyQualifiedName(), apk.getType());
    }

    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();
    StringWithMacrosConverter macrosConverter =
        StringWithMacrosConverter.of(
            buildTarget,
            context.getCellPathResolver().getCellNameResolver(),
            graphBuilder,
            MACRO_EXPANDERS);

    ImmutableMap<String, Arg> testEnv =
        ImmutableMap.copyOf(Maps.transformValues(args.getEnv(), macrosConverter::convert));

    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    ToolchainProvider toolchainProvider = context.getToolchainProvider();

    JavaOptions javaOptions = javaOptionsForTests.apply(buildTarget.getTargetConfiguration());

    return new AndroidInstrumentationTest(
        buildTarget,
        projectFilesystem,
        toolchainProvider.getByName(
            AndroidPlatformTarget.DEFAULT_NAME,
            buildTarget.getTargetConfiguration(),
            AndroidPlatformTarget.class),
        params.copyAppendingExtraDeps(BuildRules.getExportedRules(params.getDeclaredDeps().get())),
        testEnv,
        (HasInstallableApk) apk,
        args.getLabels(),
        args.getContacts(),
        javaOptions.getJavaRuntime(),
        javaOptions.getJavaRuntimeVersion(),
        args.getTestRuleTimeoutMs()
            .map(Optional::of)
            .orElse(testBuckConfig.getDefaultTestRuleTimeoutMs()),
        getRelativePackagedResource(projectFilesystem, "ddmlib.jar"),
        getRelativePackagedResource(projectFilesystem, "kxml2.jar"),
        getRelativePackagedResource(projectFilesystem, "guava.jar"),
        getRelativePackagedResource(projectFilesystem, "android-tools-common.jar"),
        downwardApiConfig.isEnabledForAndroid());
  }

  /**
   * @return The packaged resource with name {@code resourceName} from the same jar as current class
   *     with path relative to this class location.
   *     <p>Since resources like ddmlib.jar are needed for all {@link AndroidInstrumentationTest}
   *     instances it makes sense to memoize them.
   */
  private PackagedResource getRelativePackagedResource(
      ProjectFilesystem projectFilesystem, String resourceName) {
    return resourceSupplierCache
        .computeIfAbsent(projectFilesystem, fs -> new ConcurrentHashMap<>())
        .computeIfAbsent(
            resourceName,
            resource ->
                new PackagedResource(
                    projectFilesystem, AndroidInstrumentationTestDescription.class, resource));
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellNameResolver cellRoots,
      AndroidInstrumentationTestDescriptionArg constructorArg,
      Builder<BuildTarget> extraDepsBuilder,
      Builder<BuildTarget> targetGraphOnlyDepsBuilder) {}

  @RuleArg
  interface AbstractAndroidInstrumentationTestDescriptionArg extends BuildRuleArg, HasTestTimeout {
    BuildTarget getApk();

    ImmutableMap<String, StringWithMacros> getEnv();
  }
}
