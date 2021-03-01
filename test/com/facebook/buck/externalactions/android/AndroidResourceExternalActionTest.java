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
import com.facebook.buck.rules.modern.model.BuildableCommand;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.step.isolatedsteps.android.MiniAapt;
import com.facebook.buck.step.isolatedsteps.common.MkdirIsolatedStep;
import com.facebook.buck.step.isolatedsteps.common.RmIsolatedStep;
import com.facebook.buck.step.isolatedsteps.common.WriteFileIsolatedStep;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.json.ObjectMappers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Files;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.hamcrest.Matchers;
import org.junit.Test;

public class AndroidResourceExternalActionTest {

  @Test
  public void canGetSteps() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path temp = filesystem.createTempFile("android_resource_", "");
    StepExecutionContext context = TestExecutionContext.newInstance();
    AndroidResourceExternalActionArgs args =
        AndroidResourceExternalActionArgs.of(
            "res",
            "path_to_dir",
            "path_to_dir/symbols_file",
            "path_to_dir/r_dot_java_package_file",
            "path_to_manifest_file",
            ImmutableSet.of(),
            true,
            "r_dot_java_package_argument");

    try {
      String json = ObjectMappers.WRITER.writeValueAsString(args);
      Files.asCharSink(temp.toFile(), StandardCharsets.UTF_8).write(json);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to write SplitResourcesExternalActionArgs JSON", e);
    }

    BuildableCommand buildableCommand =
        BuildableCommand.newBuilder()
            .setExternalActionClass(AndroidResourceExternalAction.class.getName())
            .addExtraFiles(temp.toString())
            .build();
    ImmutableList<IsolatedStep> steps =
        new AndroidResourceExternalAction().getSteps(buildableCommand);

    assertThat(steps, Matchers.hasSize(4));

    assertThat(steps.get(0), Matchers.instanceOf(RmIsolatedStep.class));
    assertThat(steps.get(1), Matchers.instanceOf(MkdirIsolatedStep.class));

    assertThat(steps.get(2), Matchers.instanceOf(WriteFileIsolatedStep.class));
    String expectedDescription =
        Platform.detect() == Platform.WINDOWS
            ? "echo ... > path_to_dir\\r_dot_java_package_file"
            : "echo ... > path_to_dir/r_dot_java_package_file";
    assertThat(
        steps.get(2).getIsolatedStepDescription(context), Matchers.equalTo(expectedDescription));

    assertThat(steps.get(3), Matchers.instanceOf(MiniAapt.class));
    assertThat(
        steps.get(3).getIsolatedStepDescription(context),
        Matchers.equalTo("generate_resource_ids res"));
  }
}
