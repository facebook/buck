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

package com.facebook.buck.step.fs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.timing.IncrementingFakeClock;
import com.google.common.collect.ImmutableSet;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

public class TouchStepTest {
  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Test
  public void testGetShortName() {
    Path someFile = Paths.get("a/file.txt");
    TouchStep touchStep = new TouchStep(someFile);
    assertEquals("touch", touchStep.getShortName());
  }

  @Test
  public void testFileGetsCreated() throws IOException, InterruptedException {
    tmp.create();
    Path path = Paths.get("somefile");
    assertFalse(path.toFile().exists());
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem(
        new IncrementingFakeClock(TimeUnit.MILLISECONDS.toNanos(1)),
        tmp.getRoot(),
        ImmutableSet.<Path>of());
    TouchStep touchStep = new TouchStep(path);
    ExecutionContext executionContext = TestExecutionContext
        .newBuilder()
        .setProjectFilesystem(projectFilesystem)
        .build();
    touchStep.execute(executionContext);
    assertTrue(projectFilesystem.exists(path));
  }

  @Test
  public void testFileLastModifiedTimeUpdated() throws IOException, InterruptedException {
    tmp.create();
    Path path = Paths.get("somefile");
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem(
        new IncrementingFakeClock(TimeUnit.MILLISECONDS.toNanos(1)),
        tmp.getRoot(),
        ImmutableSet.of(path));
    long lastModifiedTime = projectFilesystem.getLastModifiedTime(path);
    TouchStep touchStep = new TouchStep(path);
    ExecutionContext executionContext = TestExecutionContext
        .newBuilder()
        .setProjectFilesystem(projectFilesystem)
        .build();
    touchStep.execute(executionContext);
    assertTrue(projectFilesystem.exists(path));
    assertTrue(lastModifiedTime < projectFilesystem.getLastModifiedTime(path));
  }
}
