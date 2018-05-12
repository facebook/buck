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

package com.facebook.buck.cxx;

import static org.junit.Assert.assertThat;

import com.facebook.buck.core.rules.resolver.impl.TestBuildRuleResolver;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.FileListableLinkerInputArg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.Test;

public class CxxPrepareForLinkStepTest {

  @Test
  public void testCreateCxxPrepareForLinkStep() {
    Path dummyPath = Paths.get("dummy");
    BuildRuleResolver buildRuleResolver = new TestBuildRuleResolver();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(buildRuleResolver));

    // Setup some dummy values for inputs to the CxxLinkStep
    ImmutableList<Arg> dummyArgs =
        ImmutableList.of(
            FileListableLinkerInputArg.withSourcePathArg(
                SourcePathArg.of(FakeSourcePath.of("libb.a"))));

    ImmutableList<Step> cxxPrepareForLinkStepSupportFileList =
        CxxPrepareForLinkStep.create(
            dummyPath,
            dummyPath,
            ImmutableList.of(StringArg.of("-filelist"), StringArg.of(dummyPath.toString())),
            dummyPath,
            dummyArgs,
            CxxPlatformUtils.DEFAULT_PLATFORM.getLd().resolve(buildRuleResolver),
            dummyPath,
            pathResolver);

    assertThat(cxxPrepareForLinkStepSupportFileList.size(), Matchers.equalTo(2));
    Step firstStep = cxxPrepareForLinkStepSupportFileList.get(0);
    Step secondStep = cxxPrepareForLinkStepSupportFileList.get(1);
    assertThat(firstStep, Matchers.instanceOf(CxxWriteArgsToFileStep.class));
    assertThat(secondStep, Matchers.instanceOf(CxxWriteArgsToFileStep.class));
    assertThat(firstStep, Matchers.not(secondStep));

    ImmutableList<Step> cxxPrepareForLinkStepNoSupportFileList =
        CxxPrepareForLinkStep.create(
            dummyPath,
            dummyPath,
            ImmutableList.of(),
            dummyPath,
            dummyArgs,
            CxxPlatformUtils.DEFAULT_PLATFORM.getLd().resolve(buildRuleResolver),
            dummyPath,
            pathResolver);

    assertThat(cxxPrepareForLinkStepNoSupportFileList.size(), Matchers.equalTo(1));
    assertThat(
        cxxPrepareForLinkStepNoSupportFileList.get(0),
        Matchers.instanceOf(CxxWriteArgsToFileStep.class));
  }

  @Test
  public void cxxLinkStepPassesLinkerOptionsViaArgFile() throws IOException, InterruptedException {
    ProjectFilesystem projectFilesystem = FakeProjectFilesystem.createRealTempFilesystem();
    Path argFilePath =
        projectFilesystem.getRootPath().resolve("cxxLinkStepPassesLinkerOptionsViaArgFile.txt");
    Path fileListPath =
        projectFilesystem.getRootPath().resolve("cxxLinkStepPassesLinkerOptionsViaFileList.txt");
    Path output = projectFilesystem.getRootPath().resolve("output");

    runTestForArgFilePathAndOutputPath(
        argFilePath, fileListPath, output, projectFilesystem.getRootPath());
  }

  @Test
  public void cxxLinkStepCreatesDirectoriesIfNeeded() throws IOException, InterruptedException {
    ProjectFilesystem projectFilesystem = FakeProjectFilesystem.createRealTempFilesystem();
    Path argFilePath =
        projectFilesystem.getRootPath().resolve("unexisting_parent_folder/argfile.txt");
    Path fileListPath =
        projectFilesystem.getRootPath().resolve("unexisting_parent_folder/filelist.txt");
    Path output = projectFilesystem.getRootPath().resolve("output");

    Files.deleteIfExists(argFilePath);
    Files.deleteIfExists(fileListPath);
    Files.deleteIfExists(argFilePath.getParent());
    Files.deleteIfExists(fileListPath.getParent());

    runTestForArgFilePathAndOutputPath(
        argFilePath, fileListPath, output, projectFilesystem.getRootPath());

    // cleanup after test
    Files.deleteIfExists(argFilePath);
    Files.deleteIfExists(argFilePath.getParent());
    Files.deleteIfExists(fileListPath);
    Files.deleteIfExists(fileListPath.getParent());
  }

  private void runTestForArgFilePathAndOutputPath(
      Path argFilePath, Path fileListPath, Path output, Path currentCellPath)
      throws IOException, InterruptedException {
    ExecutionContext context = TestExecutionContext.newInstance();

    BuildRuleResolver buildRuleResolver = new TestBuildRuleResolver();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(buildRuleResolver));

    // Setup some dummy values for inputs to the CxxLinkStep
    ImmutableList<Arg> args =
        ImmutableList.of(
            StringArg.of("-rpath"),
            StringArg.of("hello"),
            StringArg.of("a.o"),
            FileListableLinkerInputArg.withSourcePathArg(
                SourcePathArg.of(FakeSourcePath.of("libb.a"))),
            StringArg.of("-lsysroot"),
            StringArg.of("/Library/Application Support/blabla"),
            StringArg.of("-F/System/Frameworks"),
            StringArg.of("-L/System/libraries"),
            StringArg.of("-lz"));

    // Create our CxxLinkStep to test.
    ImmutableList<Step> steps =
        CxxPrepareForLinkStep.create(
            argFilePath,
            fileListPath,
            ImmutableList.of(StringArg.of("-filelist"), StringArg.of(fileListPath.toString())),
            output,
            args,
            CxxPlatformUtils.DEFAULT_PLATFORM.getLd().resolve(buildRuleResolver),
            currentCellPath,
            pathResolver);

    for (Step step : steps) {
      step.execute(context);
    }

    assertThat(Files.exists(argFilePath), Matchers.equalTo(true));

    ImmutableList<String> expectedArgFileContents =
        ImmutableList.<String>builder()
            .add("-o", output.toString())
            .add("-rpath")
            .add("hello")
            .add("a.o")
            .add("-lsysroot")
            .add("\"/Library/Application Support/blabla\"")
            .add("-F/System/Frameworks")
            .add("-L/System/libraries")
            .add("-lz")
            .add("-filelist")
            .add(fileListPath.toString())
            .build();

    ImmutableList<String> expectedFileListContents =
        ImmutableList.of(Paths.get("libb.a").toAbsolutePath().toString());

    checkContentsOfFile(argFilePath, expectedArgFileContents);
    checkContentsOfFile(fileListPath, expectedFileListContents);

    Files.deleteIfExists(argFilePath);
    Files.deleteIfExists(fileListPath);
  }

  private void checkContentsOfFile(Path file, ImmutableList<String> contents) throws IOException {
    List<String> fileContents = Files.readAllLines(file, StandardCharsets.UTF_8);
    assertThat(fileContents, Matchers.equalTo(contents));
  }
}
