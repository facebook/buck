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
import com.facebook.buck.core.build.buildable.context.FakeBuildableContext;
import com.facebook.buck.core.build.context.FakeBuildContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.description.impl.DescriptionCache;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.TestBuildRuleParams;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.coercer.BuildConfigFields;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

/** Unit test for {@link AndroidBuildConfig}. */
public class AndroidBuildConfigTest {

  public static final BuildTarget BUILD_TARGET =
      BuildTargetFactory.newInstance("//java/com/example:build_config");
  private static final ProjectFilesystem filesystem = new FakeProjectFilesystem();

  @Test
  public void testGetPathToOutput() {
    AndroidBuildConfig buildConfig = createSimpleBuildConfigRule();
    assertEquals(
        ExplicitBuildTargetSourcePath.of(
            BUILD_TARGET,
            BuildTargetPaths.getGenPath(filesystem, BUILD_TARGET, "__%s__/BuildConfig.java")),
        buildConfig.getSourcePathToOutput());
  }

  @Test
  public void testBuildInternal() {
    AndroidBuildConfig buildConfig = createSimpleBuildConfigRule();
    List<Step> steps =
        buildConfig.getBuildSteps(FakeBuildContext.NOOP_CONTEXT, new FakeBuildableContext());
    Step generateBuildConfigStep = steps.get(2);
    GenerateBuildConfigStep expectedStep =
        new GenerateBuildConfigStep(
            new FakeProjectFilesystem(),
            BuildTargetFactory.newInstance("//java/com/example:build_config")
                .getUnflavoredBuildTarget(),
            /* javaPackage */ "com.example",
            /* useConstantExpressions */ false,
            /* constants */ Suppliers.ofInstance(BuildConfigFields.of()),
            BuildTargetPaths.getGenPath(filesystem, BUILD_TARGET, "__%s__/BuildConfig.java"));
    assertEquals(expectedStep, generateBuildConfigStep);
  }

  @Test
  public void testGetTypeMethodOfBuilder() {
    assertEquals(
        "android_build_config",
        DescriptionCache.getRuleType(AndroidBuildConfigDescription.class).getName());
  }

  @Test
  public void testReadValuesStep() throws Exception {
    Path pathToValues = Paths.get("src/values.txt");

    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    projectFilesystem.writeLinesToPath(
        ImmutableList.of("boolean DEBUG = false", "String FOO = \"BAR\""), pathToValues);

    ReadValuesStep step = new ReadValuesStep(projectFilesystem, pathToValues);
    ExecutionContext context = TestExecutionContext.newBuilder().build();
    int exitCode = step.execute(context).getExitCode();
    assertEquals(0, exitCode);
    assertEquals(
        BuildConfigFields.fromFields(
            ImmutableList.of(
                BuildConfigFields.Field.of("boolean", "DEBUG", "false"),
                BuildConfigFields.Field.of("String", "FOO", "\"BAR\""))),
        step.get());
  }

  private static AndroidBuildConfig createSimpleBuildConfigRule() {
    // First, create the BuildConfig object.
    BuildRuleParams params = TestBuildRuleParams.create();
    return new AndroidBuildConfig(
        BUILD_TARGET,
        new FakeProjectFilesystem(),
        params,
        /* javaPackage */ "com.example",
        /* values */ BuildConfigFields.of(),
        /* valuesFile */ Optional.empty(),
        /* useConstantExpressions */ false);
  }

  // TODO(nickpalmer): Add another unit test that passes in a non-trivial DependencyGraph and verify
  // that the resulting set of libraryManifestPaths is computed correctly.
}
