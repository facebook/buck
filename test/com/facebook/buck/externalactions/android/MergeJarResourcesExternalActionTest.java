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
import com.facebook.buck.step.isolatedsteps.android.MergeJarResourcesStep;
import com.facebook.buck.util.json.ObjectMappers;
import com.google.common.collect.ImmutableList;
import com.google.common.io.Files;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.hamcrest.Matchers;
import org.junit.Test;

public class MergeJarResourcesExternalActionTest {

  @Test
  public void canGetSteps() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path temp = filesystem.createTempFile("merge_jar_resources_", "");
    StepExecutionContext context = TestExecutionContext.newInstance();

    MergeJarResourcesExternalActionArgs args =
        MergeJarResourcesExternalActionArgs.of(
            ImmutableList.of("jarPath1", "jarPath2"), "outputPath");

    try {
      String json = ObjectMappers.WRITER.writeValueAsString(args);
      Files.asCharSink(temp.toFile(), StandardCharsets.UTF_8).write(json);
    } catch (IOException e) {
      throw new IllegalStateException(
          "Failed to write MergeJarResourcesExternalActionArgs JSON", e);
    }

    BuildableCommand buildableCommand =
        BuildableCommand.newBuilder()
            .setExternalActionClass(MergeJarResourcesExternalAction.class.getName())
            .addExtraFiles(temp.toString())
            .build();
    ImmutableList<IsolatedStep> steps =
        new MergeJarResourcesExternalAction().getSteps(buildableCommand);

    assertThat(steps, Matchers.hasSize(1));

    assertThat(steps.get(0), Matchers.instanceOf(MergeJarResourcesStep.class));
    MergeJarResourcesStep actualMergeJarResourcesStep = (MergeJarResourcesStep) steps.get(0);

    String[] description = actualMergeJarResourcesStep.getDescription(context).split(" ");

    assertThat(description.length, Matchers.equalTo(3));
    assertThat(description[0], Matchers.equalTo("merge_jar_resources"));
    assertThat(description[1], Matchers.equalTo("outputPath"));

    String[] jars = description[2].split(",");
    assertThat(jars.length, Matchers.equalTo(2));
    assertThat(jars[0], Matchers.containsString("jarPath1"));
    assertThat(jars[1], Matchers.containsString("jarPath2"));
  }
}
