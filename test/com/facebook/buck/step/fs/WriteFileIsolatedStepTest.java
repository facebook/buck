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

package com.facebook.buck.step.fs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.step.isolatedsteps.common.WriteFileIsolatedStep;
import com.facebook.buck.testutil.TemporaryPaths;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;

public class WriteFileIsolatedStepTest {

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Test
  public void testFileIsWrittenWithNewline() throws Exception {

    WriteFileIsolatedStep writeFileIsolatedStep =
        WriteFileIsolatedStep.of("Hello world", Paths.get("foo.txt"), /* executable */ false);
    StepExecutionContext executionContext =
        TestExecutionContext.newInstance(temporaryFolder.getRoot());
    writeFileIsolatedStep.executeIsolatedStep(executionContext);
    assertThat(
        ProjectFilesystemUtils.readFileIfItExists(temporaryFolder.getRoot(), Paths.get("foo.txt")),
        equalTo(Optional.of("Hello world\n")));
  }
}
