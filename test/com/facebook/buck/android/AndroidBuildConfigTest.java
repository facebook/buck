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

import static org.junit.Assert.assertEquals;

import com.facebook.buck.android.AndroidBuildConfig.ReadValuesStep;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.coercer.BuildConfigFields;
import com.facebook.buck.rules.coercer.ImmutableBuildConfigFields;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.util.BuckConstant;
import com.google.common.base.Optional;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;

import org.easymock.EasyMock;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Unit test for {@link AndroidBuildConfig}.
 */
public class AndroidBuildConfigTest {

  /**
   * Tests the following methods:
   * <ul>
   *   <li>{@link AbstractBuildRule#getInputsToCompareToOutput()}
   *   <li>{@link AndroidBuildConfig#getPathToOutputFile()}
   * </ul>
   */
  @Test
  public void testSimpleObserverMethods() {
    AndroidBuildConfig buildConfig = createSimpleBuildConfigRule();

    assertEquals(
        BuckConstant.GEN_PATH.resolve("java/com/example/__build_config__/BuildConfig.java"),
        buildConfig.getPathToOutputFile());
  }

  @Test
  public void testBuildInternal() throws IOException {
    AndroidBuildConfig buildConfig = createSimpleBuildConfigRule();
    List<Step> steps = buildConfig.getBuildSteps(
        FakeBuildContext.NOOP_CONTEXT, new FakeBuildableContext());
    Step generateBuildConfigStep = steps.get(1);
    GenerateBuildConfigStep expectedStep = new GenerateBuildConfigStep(
        /* source */ BuildTargetFactory.newInstance("//java/com/example:build_config"),
        /* javaPackage */ "com.example",
        /* useConstantExpressions */ false,
        /* constants */ Suppliers.ofInstance(BuildConfigFields.empty()),
        BuckConstant.GEN_PATH.resolve("java/com/example/__build_config__/BuildConfig.java"));
    assertEquals(expectedStep, generateBuildConfigStep);
  }

  @Test
  public void testGetTypeMethodOfBuilder() {
    assertEquals("android_build_config", AndroidBuildConfigDescription.TYPE.getName());
  }

  @Test
  @SuppressWarnings("PMD.UseAssertTrueInsteadOfAssertEquals") // PMD has a bad heuristic here.
  public void testReadValuesStep() throws IOException {
    Path pathToValues = Paths.get("src/values.txt");

    ProjectFilesystem projectFilesystem = EasyMock.createMock(ProjectFilesystem.class);
    EasyMock.expect(projectFilesystem.readLines(pathToValues)).andReturn(
        ImmutableList.of("boolean DEBUG = false", "String FOO = \"BAR\""));
    EasyMock.replay(projectFilesystem);

    ReadValuesStep step = new ReadValuesStep(pathToValues);
    ExecutionContext context = TestExecutionContext
        .newBuilder()
        .setProjectFilesystem(projectFilesystem)
        .build();
    int exitCode = step.execute(context);
    assertEquals(0, exitCode);
    assertEquals(
        BuildConfigFields.fromFields(ImmutableList.<BuildConfigFields.Field>of(
            ImmutableBuildConfigFields.Field.of("boolean", "DEBUG", "false"),
            ImmutableBuildConfigFields.Field.of("String", "FOO", "\"BAR\""))),
        step.get());

    EasyMock.verify(projectFilesystem);
  }

  private static AndroidBuildConfig createSimpleBuildConfigRule() {
    // First, create the BuildConfig object.
    BuildTarget buildTarget = BuildTarget.builder("//java/com/example", "build_config").build();
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(buildTarget)
        .setType(AndroidBuildConfigDescription.TYPE)
        .build();
    return new AndroidBuildConfig(
        params,
        new SourcePathResolver(new BuildRuleResolver()),
        /* javaPackage */ "com.example",
        /* values */ BuildConfigFields.empty(),
        /* valuesFile */ Optional.<SourcePath>absent(),
        /* useConstantExpressions */ false);
  }

  // TODO(nickpalmer): Add another unit test that passes in a non-trivial DependencyGraph and verify
  // that the resulting set of libraryManifestPaths is computed correctly.
}
