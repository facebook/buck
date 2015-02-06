/*
 * Copyright 2015-present Facebook, Inc.
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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleParamsFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.google.common.collect.ImmutableList;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

public class AssembleDirectoriesTest {
  private ExecutionContext context;
  private ProjectFilesystem filesystem;

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  @Before
  public void setUp() {
    filesystem = new ProjectFilesystem(tmp.getRoot().toPath());
    context = TestExecutionContext
        .newBuilder()
        .setProjectFilesystem(filesystem)
        .build();
  }

  @Test
  public void testAssembleFoldersWithRelativePath() throws IOException, InterruptedException {
    tmp.newFolder("folder_a");
    tmp.newFile("folder_a/a.txt");
    tmp.newFile("folder_a/b.txt");
    tmp.newFolder("folder_b");
    tmp.newFile("folder_b/c.txt");
    tmp.newFile("folder_b/d.txt");

    BuildRuleParams buildRuleParams = BuildRuleParamsFactory.createTrivialBuildRuleParams(
        BuildTarget.builder("//", "output_folder").build());
    ImmutableList<SourcePath> directories = ImmutableList.<SourcePath>of(
        new TestSourcePath("folder_a"), new TestSourcePath("folder_b"));
    AssembleDirectories assembleDirectories = new AssembleDirectories(
        buildRuleParams, new SourcePathResolver(new BuildRuleResolver()), directories);
    ImmutableList<Step> steps = assembleDirectories.getBuildSteps(
        FakeBuildContext.NOOP_CONTEXT,
        new FakeBuildableContext());
    for (Step step : steps) {
      assertEquals(0, step.execute(context));
    }
    File outputFile = filesystem.resolve(assembleDirectories.getPathToOutputFile()).toFile();
    assertEquals(4, outputFile.list().length);
  }
}
