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

package com.facebook.buck.apple;

import static com.facebook.buck.apple.FakeAppleRuleDescriptions.DEFAULT_IPHONEOS_I386_PLATFORM;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.shell.AbstractGenruleStep;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.hash.HashCode;
import java.nio.file.Paths;
import org.junit.Before;
import org.junit.Test;

public class ExternallyBuiltApplePackageTest {

  private String bundleLocation = "Fake/Bundle/Location";
  private BuildTarget buildTarget = BuildTarget.builder(Paths.get("."), "//foo", "package").build();
  private BuildRuleParams params = new FakeBuildRuleParamsBuilder(buildTarget).build();
  private BuildRuleResolver resolver =
      new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
  private ApplePackageConfigAndPlatformInfo config =
      ApplePackageConfigAndPlatformInfo.of(
          ApplePackageConfig.of("echo $SDKROOT $OUT", "api"),
          StringArg::of,
          DEFAULT_IPHONEOS_I386_PLATFORM);

  @Before
  public void setUp() {
    assumeTrue(Platform.detect() == Platform.MACOS || Platform.detect() == Platform.LINUX);
  }

  @Test
  public void sdkrootEnvironmentVariableIsSet() {
    SourcePathResolver pathResolver =
        new SourcePathResolver(new SourcePathRuleFinder(this.resolver));
    ExternallyBuiltApplePackage rule =
        new ExternallyBuiltApplePackage(params, config, new FakeSourcePath(bundleLocation), true);
    resolver.addToIndex(rule);
    ShellStep step =
        Iterables.getOnlyElement(
            Iterables.filter(
                rule.getBuildSteps(
                    FakeBuildContext.withSourcePathResolver(pathResolver),
                    new FakeBuildableContext()),
                AbstractGenruleStep.class));
    assertThat(
        step.getEnvironmentVariables(TestExecutionContext.newInstance()),
        hasEntry(
            "SDKROOT", DEFAULT_IPHONEOS_I386_PLATFORM.getAppleSdkPaths().getSdkPath().toString()));
  }

  @Test
  public void outputContainsCorrectExtension() {
    SourcePathResolver pathResolver =
        new SourcePathResolver(new SourcePathRuleFinder(this.resolver));
    ExternallyBuiltApplePackage rule =
        new ExternallyBuiltApplePackage(
            params, config, new FakeSourcePath("Fake/Bundle/Location"), true);
    resolver.addToIndex(rule);
    assertThat(
        pathResolver
            .getRelativePath(Preconditions.checkNotNull(rule.getSourcePathToOutput()))
            .toString(),
        endsWith(".api"));
  }

  @Test
  public void commandContainsCorrectCommand() {
    SourcePathResolver pathResolver =
        new SourcePathResolver(new SourcePathRuleFinder(this.resolver));
    ExternallyBuiltApplePackage rule =
        new ExternallyBuiltApplePackage(
            params, config, new FakeSourcePath("Fake/Bundle/Location"), true);
    resolver.addToIndex(rule);
    AbstractGenruleStep step =
        Iterables.getOnlyElement(
            Iterables.filter(
                rule.getBuildSteps(
                    FakeBuildContext.withSourcePathResolver(pathResolver),
                    new FakeBuildableContext()),
                AbstractGenruleStep.class));
    assertThat(
        step.getScriptFileContents(TestExecutionContext.newInstance()),
        is(equalTo("echo $SDKROOT $OUT")));
  }

  @Test
  public void platformVersionAffectsRuleKey() {
    Function<String, ExternallyBuiltApplePackage> packageWithVersion =
        input ->
            new ExternallyBuiltApplePackage(
                params,
                config.withPlatform(config.getPlatform().withBuildVersion(input)),
                new FakeSourcePath("Fake/Bundle/Location"),
                true);
    assertNotEquals(
        newRuleKeyFactory().build(packageWithVersion.apply("real")),
        newRuleKeyFactory().build(packageWithVersion.apply("fake")));
  }

  @Test
  public void sdkVersionAffectsRuleKey() {
    Function<String, ExternallyBuiltApplePackage> packageWithSdkVersion =
        input ->
            new ExternallyBuiltApplePackage(
                params,
                config.withPlatform(
                    config
                        .getPlatform()
                        .withAppleSdk(config.getPlatform().getAppleSdk().withVersion(input))),
                new FakeSourcePath("Fake/Bundle/Location"),
                true);
    assertNotEquals(
        newRuleKeyFactory().build(packageWithSdkVersion.apply("real")),
        newRuleKeyFactory().build(packageWithSdkVersion.apply("fake")));
  }

  private DefaultRuleKeyFactory newRuleKeyFactory() {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    return new DefaultRuleKeyFactory(
        0,
        new FakeFileHashCache(
            ImmutableMap.of(Paths.get(bundleLocation).toAbsolutePath(), HashCode.fromInt(5))),
        new SourcePathResolver(ruleFinder),
        ruleFinder);
  }
}
