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

package com.facebook.buck.shell;

import static org.junit.Assert.assertTrue;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.collect.ImmutableSet;

import org.easymock.EasyMock;
import org.easymock.EasyMockSupport;
import org.junit.After;
import org.junit.Test;

public class ShTestTest extends EasyMockSupport {

  @After
  public void tearDown() {
    // I don't understand why EasyMockSupport doesn't do this by default.
    verifyAll();
  }

  @Test
  public void testHasTestResultFiles() {
    ShTest shTest = new ShTest(
        new BuildTarget("//test/com/example", "my_sh_test"),
        new TestSourcePath("run_test.sh"),
        /* labels */ ImmutableSet.<Label>of());

    ProjectFilesystem filesystem = createMock(ProjectFilesystem.class);
    EasyMock.expect(filesystem.isFile(shTest.getPathToTestOutputResult())).andReturn(true);
    ExecutionContext executionContext = createMock(ExecutionContext.class);
    EasyMock.expect(executionContext.getProjectFilesystem()).andReturn(filesystem);

    replayAll();

    assertTrue("hasTestResultFiles() should return true if result.json exists.",
        shTest.hasTestResultFiles(executionContext));
  }
}
