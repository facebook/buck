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

package com.facebook.buck.cli.endtoend;

import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.io.filesystem.BuckPaths;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.endtoend.EndToEndEnvironment;
import com.facebook.buck.testutil.endtoend.EndToEndRunner;
import com.facebook.buck.testutil.endtoend.EndToEndTestDescriptor;
import com.facebook.buck.testutil.endtoend.EndToEndWorkspace;
import com.facebook.buck.testutil.endtoend.Environment;
import com.facebook.buck.testutil.endtoend.EnvironmentFor;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(EndToEndRunner.class)
public class BaseDirEndToEndTest {

  @Environment
  public static EndToEndEnvironment getBaseDirSetEnvironment() {
    return new EndToEndEnvironment()
        .withCommand("build")
        .addLocalConfigSet(
            ImmutableMap.of("parser", ImmutableMap.of("default_build_file_syntax", "SKYLARK")))
        .addLocalConfigSet(
            ImmutableMap.of("parser", ImmutableMap.of("default_build_file_syntax", "PYTHON_DSL")));
  }

  @EnvironmentFor(testNames = {"usingBaseDirShouldNotTouchBuckOutDir"})
  public static EndToEndEnvironment setSuccessfulTargetPath() {
    return getBaseDirSetEnvironment()
        .addTemplates("cxx")
        .withTargets("simple_successful_helloworld");
  }

  @Test
  public void usingBaseDirShouldNotTouchBuckOutDir(
      EndToEndTestDescriptor test, EndToEndWorkspace workspace) throws Throwable {
    for (String template : test.getTemplateSet()) {
      workspace.addPremadeTemplate(template);
    }

    ProcessResult result =
        workspace.runBuckCommand(
            "--isolation_prefix",
            "mybase",
            "build",
            "//simple_successful_helloworld:simple_successful_helloworld");
    result.assertSuccess();

    Path output =
        workspace
            .getDestPath()
            .resolve(Paths.get("mybase-buck-out", "gen"))
            .resolve(
                BuildTargetPaths.getBasePath(
                        FakeProjectFilesystem.createFilesystemWithTargetConfigHashInBuckPaths(
                            BuckPaths.DEFAULT_BUCK_OUT_INCLUDE_TARGET_CONFIG_HASH),
                        BuildTargetFactory.newInstance(
                            "//simple_successful_helloworld:simple_successful_helloworld"),
                        "%s")
                    .toPath(workspace.getDestPath().getFileSystem()));
    Assert.assertTrue(Files.exists(output));

    Path usualOutput =
        workspace
            .getDestPath()
            .resolve(Paths.get("buck-out", "gen"))
            .resolve(
                BuildTargetPaths.getBasePath(
                        FakeProjectFilesystem.createFilesystemWithTargetConfigHashInBuckPaths(
                            BuckPaths.DEFAULT_BUCK_OUT_INCLUDE_TARGET_CONFIG_HASH),
                        BuildTargetFactory.newInstance(
                            "//simple_successful_helloworld:simple_successful_helloworld"),
                        "%s")
                    .toPath(workspace.getDestPath().getFileSystem()));
    Assert.assertFalse(Files.exists(usualOutput));

    workspace.deleteRecursivelyIfExists(output.toString());

    result =
        workspace.runBuckCommand(
            "build", "//simple_successful_helloworld:simple_successful_helloworld");
    result.assertSuccess();

    Assert.assertFalse(Files.exists(output));
    Assert.assertTrue(Files.exists(usualOutput));
  }
}
