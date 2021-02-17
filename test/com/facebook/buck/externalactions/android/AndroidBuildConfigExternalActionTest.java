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

package com.facebook.buck.externalactions.android;

import static org.hamcrest.MatcherAssert.assertThat;

import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.coercer.BuildConfigFields;
import com.facebook.buck.rules.modern.model.BuildableCommand;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.step.isolatedsteps.android.GenerateBuildConfigStep;
import com.facebook.buck.util.json.ObjectMappers;
import com.google.common.collect.ImmutableList;
import com.google.common.io.Files;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Test;

public class AndroidBuildConfigExternalActionTest {

  @Test
  public void canGetSteps() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path temp = filesystem.createTempFile("android_manifest_", "");
    StepExecutionContext context = TestExecutionContext.newInstance();
    AndroidBuildConfigExternalActionArgs args =
        AndroidBuildConfigExternalActionArgs.of(
            "build_config",
            /* javaPackage */ "com.example",
            /* useConstantExpressions */ false,
            Optional.empty(),
            ImmutableList.of(BuildConfigFields.Field.of("boolean", "DEBUG", "false").toString()),
            "output");

    try {
      String json = ObjectMappers.WRITER.writeValueAsString(args);
      Files.asCharSink(temp.toFile(), StandardCharsets.UTF_8).write(json);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to write SplitResourcesExternalActionArgs JSON", e);
    }

    BuildableCommand buildableCommand =
        BuildableCommand.newBuilder()
            .setExternalActionClass(AndroidBuildConfigExternalAction.class.getName())
            .addExtraFiles(temp.toString())
            .build();
    ImmutableList<IsolatedStep> steps =
        new AndroidBuildConfigExternalAction().getSteps(buildableCommand);

    assertThat(steps, Matchers.hasSize(1));

    assertThat(steps.get(0), Matchers.instanceOf(GenerateBuildConfigStep.class));
    GenerateBuildConfigStep generateBuildConfigStep = (GenerateBuildConfigStep) steps.get(0);
    assertThat(
        generateBuildConfigStep.getDescription(context),
        Matchers.equalTo("generate_build_config com.example"));
  }
}
