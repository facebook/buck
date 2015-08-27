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

package com.facebook.buck.step.fs;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;

import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Optional;

import org.junit.Test;

import java.nio.file.Paths;

public class WriteFileStepTest {

  @Test
  public void testFileIsWrittenWithNewline() throws Exception {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    WriteFileStep writeFileStep =
        new WriteFileStep(filesystem, "Hello world", Paths.get("foo.txt"), /* executable */ false);
    ExecutionContext executionContext = TestExecutionContext
        .newBuilder()
        .setProjectFilesystem(filesystem)
        .build();
    writeFileStep.execute(executionContext);
    assertThat(
        filesystem.readFileIfItExists(Paths.get("foo.txt")),
        equalTo(Optional.of("Hello world\n")));
  }
}
