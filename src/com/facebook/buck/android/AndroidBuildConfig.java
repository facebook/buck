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

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.externalactions.android.AndroidBuildConfigExternalAction;
import com.facebook.buck.externalactions.android.AndroidBuildConfigExternalActionArgs;
import com.facebook.buck.externalactions.utils.ExternalActionsUtils;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.coercer.BuildConfigFields;
import com.facebook.buck.rules.modern.BuildableWithExternalAction;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.rules.modern.model.BuildableCommand;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@link BuildRule} that can generate a {@code BuildConfig.java} file and compile it so it can be
 * used as a Java library.
 *
 * <p>This rule functions as a {@code java_library} that can be used as a dependency of an {@code
 * android_library}, but whose implementation may be swapped out by the {@code android_binary} that
 * transitively includes the {@code android_build_config}. Specifically, its compile-time
 * implementation will use non-constant-expression (see JLS 15.28), placeholder values (because they
 * cannot be inlined) for the purposes of compilation that will be swapped out with final,
 * production values (that can be inlined) when building the final APK. Consider the following
 * example:
 *
 * <pre>
 * android_build_config(
 *   name = 'build_config',
 *   package = 'com.example.pkg',
 * )
 *
 * # The .java files in this library may contain references to the boolean
 * # com.example.pkg.BuildConfig.DEBUG because :build_config is in the deps.
 * android_library(
 *   name = 'mylib',
 *   srcs = glob(['src/**&#47;*.java']),
 *   deps = [
 *     ':build_config',
 *   ],
 * )
 *
 * android_binary(
 *   name = 'debug',
 *   package_type = 'DEBUG',
 *   keystore =  '//keystores:debug',
 *   manifest = 'AndroidManifest.xml',
 *   target = 'Google Inc.:Google APIs:19',
 *   deps = [
 *     ':mylib',
 *   ],
 * )
 *
 * android_binary(
 *   name = 'release',
 *   package_type = 'RELEASE',
 *   keystore =  '//keystores:release',
 *   manifest = 'AndroidManifest.xml',
 *   target = 'Google Inc.:Google APIs:19',
 *   deps = [
 *     ':mylib',
 *   ],
 * )
 * </pre>
 *
 * The {@code :mylib} rule will be compiled against a version of {@code BuildConfig.java} whose
 * contents are:
 *
 * <pre>
 * package com.example.pkg;
 * public class BuildConfig {
 *   private BuildConfig() {}
 *   public static final boolean DEBUG = !Boolean.parseBoolean(null);
 * }
 * </pre>
 *
 * Note that the value is not a constant expression, so it cannot be inlined by {@code javac}. When
 * building {@code :debug} and {@code :release}, the {@code BuildConfig.class} file that {@code
 * :mylib} was compiled against will not be included in the APK as the other transitive Java deps of
 * the {@code android_binary} will. The {@code BuildConfig.class} will be replaced with one that
 * corresponds to the value of the {@code package_type} argument to the {@code android_binary} rule.
 * For example, {@code :debug} will include a {@code BuildConfig.class} file that is compiled from:
 *
 * <pre>
 * package com.example.pkg;
 * public class BuildConfig {
 *   private BuildConfig() {}
 *   public static final boolean DEBUG = true;
 * }
 * </pre>
 *
 * whereas {@code :release} will include a {@code BuildConfig.class} file that is compiled from:
 *
 * <pre>
 * package com.example.pkg;
 * public class BuildConfig {
 *   private BuildConfig() {}
 *   public static final boolean DEBUG = false;
 * }
 * </pre>
 *
 * This swap happens before ProGuard is run as part of building the APK, so it will be able to
 * exploit the "final-ness" of the {@code DEBUG} constant in any whole-program optimization that it
 * performs.
 */
public class AndroidBuildConfig extends ModernBuildRule<AndroidBuildConfig.Impl> {

  protected AndroidBuildConfig(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder sourcePathRuleFinder,
      String javaPackage,
      BuildConfigFields defaultValues,
      Optional<SourcePath> valuesFile,
      boolean useConstantExpressions,
      boolean shouldExecuteInSeparateProcess,
      Tool javaRuntimeLauncher,
      Supplier<SourcePath> externalActionsSourcePathSupplier) {
    super(
        buildTarget,
        projectFilesystem,
        sourcePathRuleFinder,
        new Impl(
            buildTarget,
            javaPackage,
            defaultValues,
            valuesFile,
            useConstantExpressions,
            new OutputPath("BuildConfig.java"),
            shouldExecuteInSeparateProcess,
            javaRuntimeLauncher,
            externalActionsSourcePathSupplier));
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return getSourcePath(getBuildable().outputPath);
  }

  public String getJavaPackage() {
    return getBuildable().javaPackage;
  }

  public boolean isUseConstantExpressions() {
    return getBuildable().useConstantExpressions;
  }

  public BuildConfigFields getBuildConfigFields() {
    return getBuildable().defaultValues;
  }

  static class Impl extends BuildableWithExternalAction {
    private static final String TEMP_FILE_PREFIX = "android_build_config_";
    private static final String TEMP_FILE_SUFFIX = "";

    @AddToRuleKey private final BuildTarget buildTarget;
    @AddToRuleKey private final String javaPackage;

    @AddToRuleKey(stringify = true)
    private final BuildConfigFields defaultValues;

    @AddToRuleKey private final Optional<SourcePath> valuesFile;
    @AddToRuleKey private final boolean useConstantExpressions;
    @AddToRuleKey private final OutputPath outputPath;

    private Impl(
        BuildTarget buildTarget,
        String javaPackage,
        BuildConfigFields defaultValues,
        Optional<SourcePath> valuesFile,
        boolean useConstantExpressions,
        OutputPath outputPath,
        boolean shouldExecuteInSeparateProcess,
        Tool javaRuntimeLauncher,
        Supplier<SourcePath> externalActionsSourcePathSupplier) {
      super(shouldExecuteInSeparateProcess, javaRuntimeLauncher, externalActionsSourcePathSupplier);
      this.buildTarget = buildTarget;
      this.javaPackage = javaPackage;
      this.defaultValues = defaultValues;
      this.valuesFile = valuesFile;
      this.useConstantExpressions = useConstantExpressions;
      this.outputPath = outputPath;
    }

    @Override
    public BuildableCommand getBuildableCommand(
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildContext buildContext) {
      Path jsonFilePath = createTempFile(filesystem);
      AndroidBuildConfigExternalActionArgs jsonArgs =
          AndroidBuildConfigExternalActionArgs.of(
              buildTarget.getUnflavoredBuildTarget().toString(),
              javaPackage,
              useConstantExpressions,
              valuesFile.map(
                  file ->
                      buildContext
                          .getSourcePathResolver()
                          .getRelativePath(filesystem, file)
                          .toString()),
              defaultValues.getNameToField().values().stream()
                  .map(BuildConfigFields.Field::toString)
                  .collect(ImmutableList.toImmutableList()),
              outputPathResolver.resolvePath(outputPath).toString());
      ExternalActionsUtils.writeJsonArgs(jsonFilePath, jsonArgs);

      return BuildableCommand.newBuilder()
          .addExtraFiles(jsonFilePath.toString())
          .setExternalActionClass(AndroidBuildConfigExternalAction.class.getName())
          .build();
    }

    private Path createTempFile(ProjectFilesystem filesystem) {
      try {
        return filesystem.createTempFile(TEMP_FILE_PREFIX, TEMP_FILE_SUFFIX);
      } catch (IOException e) {
        throw new IllegalStateException(
            "Failed to create temp file when creating android build config buildable command");
      }
    }
  }
}
