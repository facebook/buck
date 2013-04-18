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

package com.facebook.buck.cli;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.util.CapturingPrintStream;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;

import org.easymock.EasyMock;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.PrintStream;

public class GenericBuckOptionsTest {

  private CapturingPrintStream stdOut;
  private PrintStream stdErr;

  @Before
  public void setUp() {
    this.stdOut = new CapturingPrintStream();
    this.stdErr = EasyMock.createMock(PrintStream.class);
    EasyMock.replay(this.stdErr);
  }

  @After
  public void tearDown() {
    this.stdOut.close();
    this.stdOut = null;
    EasyMock.verify(this.stdErr);
    this.stdErr = null;
  }

  @Test
  public void testShowVersion() throws IOException {
    GenericBuckOptions options = new GenericBuckOptions(stdOut,  stdErr, "<one>", true);
    assertEquals(0, options.execute(new String[]{"--version"}));

    options = new GenericBuckOptions(stdOut,  stdErr, "<two>", false);
    assertEquals(0, options.execute(new String[]{"--version"}));

    assertEquals(Joiner.on("\n").join("buck version *<one>", "buck version <two>", ""),
        stdOut.getContentsAsString(Charsets.US_ASCII));
  }

  @Test
  public void testShowHelp() throws IOException {
    GenericBuckOptions options = new GenericBuckOptions(stdOut,  stdErr, "n/a", false);
    assertEquals(GenericBuckOptions.SHOW_MAIN_HELP_SCREEN_EXIT_CODE,
        options.execute(new String[]{"--help"}));

    assertEquals("", stdOut.getContentsAsString(Charsets.US_ASCII));
  }
}
