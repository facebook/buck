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

package com.facebook.buck.step.fs;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.testutil.MoreAsserts;
import com.google.common.collect.ImmutableList;

import org.easymock.EasyMock;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class CopyStepTest {

  private ExecutionContext context;

  @Before
  public void setUp() {
    context = EasyMock.createMock(ExecutionContext.class);
    EasyMock.replay(context);
  }

  @After
  public void tearDown() {
    EasyMock.verify(context);
  }

  @Test
  public void testGetShellCommandInternal() {
    String source = "path/to/source.txt";
    String destination = "path/to/destination.txt";
    CopyStep copyCommand = new CopyStep(source, destination);
    MoreAsserts.assertListEquals(
        ImmutableList.of("cp", "path/to/source.txt", "path/to/destination.txt"),
        copyCommand.getShellCommand(context));
  }

  @Test
  public void testGetShellCommandInternalWithRecurse() {
    String source = "path/to/source";
    String destination = "path/to/destination";
    CopyStep copyCommand = new CopyStep(source, destination, /* shouldRecurse */ true);
    MoreAsserts.assertListEquals(
        ImmutableList.of("cp", "-R", "path/to/source", "path/to/destination"),
        copyCommand.getShellCommand(context));
  }

  @Test
  public void testGetShortName() {
    CopyStep copyCommand = new CopyStep("here", "there");
    assertEquals("cp", copyCommand.getShortName());
  }

}
