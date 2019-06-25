/*
 * Copyright 2019-present Facebook, Inc.
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

package com.facebook.buck.cli.endtoend;

import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.io.filesystem.impl.DefaultProjectFilesystem;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.endtoend.EndToEndEnvironment;
import com.facebook.buck.testutil.endtoend.EndToEndRunner;
import com.facebook.buck.testutil.endtoend.EndToEndTestDescriptor;
import com.facebook.buck.testutil.endtoend.EndToEndWorkspace;
import com.facebook.buck.testutil.endtoend.Environment;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Paths;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(EndToEndRunner.class)
public class FixEndToEndTest {

  @Environment
  public static EndToEndEnvironment getBaseEnvironment() {
    return new EndToEndEnvironment().withCommand("run").addTemplates("fix");
  }

  private ProcessResult buckCommand(
      EndToEndWorkspace workspace, EndToEndTestDescriptor test, String... command)
      throws Exception {
    return workspace.runBuckCommand(
        false, ImmutableMap.copyOf(test.getVariableMap()), test.getTemplateSet(), command);
  }

  @Test
  public void shouldRunLegacyFixScript(EndToEndTestDescriptor test, EndToEndWorkspace workspace)
      throws Throwable {
    // Windows cannot execute always execute .py files directly
    assumeTrue(!Platform.detect().equals(Platform.WINDOWS));

    // Make sure we get '0' back in the normal case, but also make sure that we error in a
    // place where the script should fail

    DefaultProjectFilesystem fs =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());
    fs.mkdirs(
        Paths.get(
            "buck-out",
            "log",
            "2019-05-21_15h18m53s_buildcommand_2ef9b523-fc4e-48a3-aa9f-baab9ca36386"));
    fs.createSymLink(
        Paths.get("buck-out", "log", "last_buildcommand"),
        Paths.get("2019-05-21_15h18m53s_buildcommand_2ef9b523-fc4e-48a3-aa9f-baab9ca36386"),
        false);

    buckCommand(workspace, test, "fix").assertSuccess();

    workspace.deleteRecursivelyIfExists(
        workspace.resolve("buck-out").resolve("log").resolve("last_buildcommand").toString());
    ProcessResult failureResult = buckCommand(workspace, test, "fix");
    assertThat(
        failureResult.getStderr(),
        Matchers.matchesPattern(
            Pattern.compile(".*No such file or directory:.*last_buildcommand.*", Pattern.DOTALL)));
  }
}
