/*
 * Copyright 2012-present Facebook, Inc.
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

import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TestBuildRuleParams;
import com.facebook.buck.rules.TestBuildRuleResolver;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.toolchain.impl.ToolchainProviderBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class GenAidlTest {

  @Test
  public void testSimpleGenAidlRule() throws IOException {
    ProjectFilesystem stubFilesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    Files.createDirectories(stubFilesystem.getRootPath().resolve("java/com/example/base"));

    PathSourcePath pathToAidl =
        FakeSourcePath.of(stubFilesystem, "java/com/example/base/IWhateverService.aidl");
    String importPath = Paths.get("java/com/example/base").toString();

    String pathToAidlExecutable = Paths.get("/usr/local/bin/aidl").toString();
    String pathToFrameworkAidl =
        Paths.get("/home/root/android/platforms/android-16/framework.aidl").toString();
    AndroidPlatformTarget androidPlatformTarget =
        AndroidPlatformTarget.of(
            "android",
            Paths.get(""),
            Collections.emptyList(),
            Paths.get(""),
            Paths.get(""),
            Paths.get(""),
            Paths.get(pathToAidlExecutable),
            Paths.get(""),
            Paths.get(""),
            Paths.get(pathToFrameworkAidl),
            Paths.get(""),
            Paths.get(""),
            Paths.get(""));

    BuildTarget target =
        BuildTargetFactory.newInstance(
            stubFilesystem.getRootPath(), "//java/com/example/base:IWhateverService");
    BuildRuleParams params = TestBuildRuleParams.create();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(new TestBuildRuleResolver()));
    GenAidl genAidlRule =
        new GenAidl(
            target,
            stubFilesystem,
            new ToolchainProviderBuilder()
                .withToolchain(AndroidPlatformTarget.DEFAULT_NAME, androidPlatformTarget)
                .build(),
            params,
            pathToAidl,
            importPath);

    GenAidlDescription description = new GenAidlDescription();
    assertEquals(
        Description.getBuildRuleType(GenAidlDescription.class),
        Description.getBuildRuleType(description));

    BuildContext buildContext =
        FakeBuildContext.withSourcePathResolver(pathResolver)
            .withBuildCellRootPath(stubFilesystem.getRootPath());
    List<Step> steps = genAidlRule.getBuildSteps(buildContext, new FakeBuildableContext());

    Path outputDirectory = BuildTargets.getScratchPath(stubFilesystem, target, "__%s.aidl");
    assertEquals(
        RmStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    buildContext.getBuildCellRootPath(), stubFilesystem, outputDirectory))
            .withRecursive(true),
        steps.get(2));
    assertEquals(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildContext.getBuildCellRootPath(), stubFilesystem, outputDirectory)),
        steps.get(3));

    ShellStep aidlStep = (ShellStep) steps.get(4);
    assertEquals(
        "gen_aidl() should use the aidl binary to write .java files.",
        String.format(
            "(cd %s && %s -p%s -I%s -o%s %s)",
            stubFilesystem.getRootPath(),
            pathToAidlExecutable,
            pathToFrameworkAidl,
            stubFilesystem.resolve(importPath),
            stubFilesystem.resolve(outputDirectory),
            pathToAidl.getRelativePath()),
        aidlStep.getDescription(TestExecutionContext.newBuilder().build()));

    assertEquals(7, steps.size());
  }
}
