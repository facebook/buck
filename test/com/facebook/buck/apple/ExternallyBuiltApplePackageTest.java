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
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasItem;
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
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.keys.DefaultRuleKeyBuilderFactory;
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

import org.junit.Before;
import org.junit.Test;

import java.nio.file.Paths;

public class ExternallyBuiltApplePackageTest {

  private String bundleLocation = "Fake/Bundle/Location";
  private BuildTarget buildTarget = BuildTarget.builder(Paths.get("."), "//foo", "package").build();
  private BuildRuleParams params = new FakeBuildRuleParamsBuilder(buildTarget).build();
  private BuildRuleResolver resolver = new BuildRuleResolver(
      TargetGraph.EMPTY,
      new DefaultTargetNodeToBuildRuleTransformer());
  private ApplePackageConfigAndPlatformInfo config =
      ApplePackageConfigAndPlatformInfo.of(
          ApplePackageConfig.of("echo $SDKROOT $OUT", "api"),
          new Function<String, Arg>() {
            @Override
            public Arg apply(String input) {
              return new StringArg(input);
            }
          },
          DEFAULT_IPHONEOS_I386_PLATFORM);

  @Before
  public void setUp() {
    assumeTrue(Platform.detect() == Platform.MACOS || Platform.detect() == Platform.LINUX);
  }


  @Test
  public void sdkrootEnvironmentVariableIsSet() {
    ExternallyBuiltApplePackage rule = new ExternallyBuiltApplePackage(
        params,
        new SourcePathResolver(resolver),
        config,
        new FakeSourcePath(bundleLocation));
    ShellStep step = Iterables.getOnlyElement(
        Iterables.filter(
            rule.getBuildSteps(FakeBuildContext.NOOP_CONTEXT, new FakeBuildableContext()),
            AbstractGenruleStep.class));
    assertThat(
        step.getEnvironmentVariables(TestExecutionContext.newInstance()),
        hasEntry(
            "SDKROOT",
            DEFAULT_IPHONEOS_I386_PLATFORM.getAppleSdkPaths().getSdkPath().toString()));
  }

  @Test
  public void outputContainsCorrectExtension() {
    ExternallyBuiltApplePackage rule = new ExternallyBuiltApplePackage(
        params,
        new SourcePathResolver(resolver),
        config,
        new FakeSourcePath("Fake/Bundle/Location"));
    assertThat(Preconditions.checkNotNull(rule.getPathToOutput()).toString(), endsWith(".api"));
  }

  @Test
  public void commandContainsCorrectCommand() {
    ExternallyBuiltApplePackage rule = new ExternallyBuiltApplePackage(
        params,
        new SourcePathResolver(resolver),
        config,
        new FakeSourcePath("Fake/Bundle/Location"));
    ShellStep step = Iterables.getOnlyElement(
        Iterables.filter(
            rule.getBuildSteps(FakeBuildContext.NOOP_CONTEXT, new FakeBuildableContext()),
            AbstractGenruleStep.class));
    assertThat(
        step.getShellCommand(TestExecutionContext.newInstance()),
        hasItem("echo $SDKROOT $OUT"));
  }

  @Test
  public void platformVersionAffectsRuleKey() {
    Function<String, ExternallyBuiltApplePackage> packageWithVersion =
        new Function<String, ExternallyBuiltApplePackage>() {
          @Override
          public ExternallyBuiltApplePackage apply(String input) {
            return new ExternallyBuiltApplePackage(
                params,
                new SourcePathResolver(resolver),
                config.withPlatform(config.getPlatform().withBuildVersion(input)),
                new FakeSourcePath("Fake/Bundle/Location"));
          }
        };
    assertNotEquals(
        newRuleKeyBuilderFactory().build(packageWithVersion.apply("real")),
        newRuleKeyBuilderFactory().build(packageWithVersion.apply("fake")));
  }

  @Test
  public void sdkVersionAffectsRuleKey() {
    Function<String, ExternallyBuiltApplePackage> packageWithSdkVersion =
        new Function<String, ExternallyBuiltApplePackage>() {
          @Override
          public ExternallyBuiltApplePackage apply(String input) {
            return new ExternallyBuiltApplePackage(
                params,
                new SourcePathResolver(resolver),
                config.withPlatform(
                    config.getPlatform().withAppleSdk(
                        config.getPlatform().getAppleSdk().withVersion(input))),
                new FakeSourcePath("Fake/Bundle/Location"));
          }
        };
    assertNotEquals(
        newRuleKeyBuilderFactory().build(packageWithSdkVersion.apply("real")),
        newRuleKeyBuilderFactory().build(packageWithSdkVersion.apply("fake")));
  }

  private DefaultRuleKeyBuilderFactory newRuleKeyBuilderFactory() {
    return new DefaultRuleKeyBuilderFactory(
        new FakeFileHashCache(
            ImmutableMap.of(Paths.get(bundleLocation).toAbsolutePath(), HashCode.fromInt(5))),
        new SourcePathResolver(resolver));
  }
}
