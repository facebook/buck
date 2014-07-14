/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.android;

import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.nio.file.Path;
import java.util.Map;

/**
 * {@link BuildRule} that can generate a {@code BuildConfig.java} file and compile it so it can be
 * used as a Java library.
 * <p>
 * This rule functions as a {@code java_library} that can be used as a dependency of an
 * {@code android_library}, but whose implementation may be swapped out by the
 * {@code android_binary} that transitively includes the {@code android_build_config}. Specifically,
 * its compile-time implementation will use non-constant-expression (see JLS 15.28), placeholder
 * values (because they cannot be inlined) for the purposes of compilation that will be swapped out
 * with final, production values (that can be inlined) when building the final APK. Consider the
 * following example:
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
 * The {@code :mylib} rule will be compiled against a version of {@code BuildConfig.java} whose
 * contents are:
 * <pre>
 * package com.example.pkg;
 * public class BuildConfig {
 *   private BuildConfig() {}
 *   public static final boolean DEBUG = !Boolean.parseBoolean(null);
 * }
 * </pre>
 * Note that the value is not a constant expression, so it cannot be inlined by {@code javac}. When
 * building {@code :debug} and {@code :release}, the {@code BuildConfig.class} file that
 * {@code :mylib} was compiled against will not be included in the APK as the other transitive Java
 * deps of the {@code android_binary} will. The {@code BuildConfig.class} will be replaced with one
 * that corresponds to the value of the {@code package_type} argument to the {@code android_binary}
 * rule. For example, {@code :debug} will include a {@code BuildConfig.class} file that is compiled
 * from:
 * <pre>
 * package com.example.pkg;
 * public class BuildConfig {
 *   private BuildConfig() {}
 *   public static final boolean DEBUG = true;
 * }
 * </pre>
 * whereas {@code :release} will include a {@code BuildConfig.class} file that is compiled from:
 * <pre>
 * package com.example.pkg;
 * public class BuildConfig {
 *   private BuildConfig() {}
 *   public static final boolean DEBUG = false;
 * }
 * </pre>
 * This swap happens before ProGuard is run as part of building the APK, so it will be able to
 * exploit the "final-ness" of the {@code DEBUG} constant in any whole-program optimization that it
 * performs.
 */
public class AndroidBuildConfig extends AbstractBuildRule {

  private final String javaPackage;
  private final boolean useConstantExpressions;
  private final ImmutableMap<String, Object> constants;
  private final Path pathToOutputFile;

  protected AndroidBuildConfig(
      BuildRuleParams buildRuleParams,
      String javaPackage,
      boolean useConstantExpressions,
      Map<String, Object> constants) {
    super(buildRuleParams);
    this.javaPackage = Preconditions.checkNotNull(javaPackage);
    this.useConstantExpressions = useConstantExpressions;
    this.constants = ImmutableMap.copyOf(constants);
    this.pathToOutputFile = BuildTargets.getGenPath(buildRuleParams.getBuildTarget(), "__%s__")
        .resolve("BuildConfig.java");
  }

  @Override
  public ImmutableCollection<Path> getInputsToCompareToOutput() {
    return ImmutableList.of();
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder
        .set("package", javaPackage);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    steps.add(new MakeCleanDirectoryStep(pathToOutputFile.getParent()));
    steps.add(new GenerateBuildConfigStep(
        javaPackage,
        useConstantExpressions,
        constants,
        pathToOutputFile));

    buildableContext.recordArtifact(pathToOutputFile);
    return steps.build();
  }

  @Override
  public Path getPathToOutputFile() {
    return pathToOutputFile;
  }

  public String getJavaPackage() {
    return javaPackage;
  }

  public boolean isUseConstantExpressions() {
    return useConstantExpressions;
  }

  public ImmutableMap<String, Object> getConstants() {
    return constants;
  }
}
