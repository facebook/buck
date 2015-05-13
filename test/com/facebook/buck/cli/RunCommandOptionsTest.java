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

package com.facebook.buck.cli;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.kohsuke.args4j.CmdLineException;

public class RunCommandOptionsTest {

  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  private String[] testWithArgs(String[] args) throws CmdLineException {
    RunCommand command = new RunCommand();
    AdditionalOptionsCmdLineParser parser = new AdditionalOptionsCmdLineParser(command);
    parser.parseArgument(args);
    return command.getArguments().toArray(new String[command.getArguments().size()]);
  }

  @Test
  public void testNormalExecution() throws CmdLineException {
    String[] args = new String[] { "//some/target", "arg1", "arg2", "arg3" };
    String[] expectedArgs = new String[] { "//some/target", "arg1", "arg2", "arg3" };
    assertArrayEquals(expectedArgs, testWithArgs(args));
  }

  @Test
  public void testInvalidOptions1() throws CmdLineException {
    String[] args = new String[] { "--invalid", "//some/target", "arg" };
    expectedException.expect(CmdLineException.class);
    expectedException.expectMessage("\"--invalid\" is not a valid option");
    testWithArgs(args);
  }

  @Test
  public void testInvalidOptions2() throws CmdLineException {
    String[] args = new String[] { "//some/target", "--invalid", "arg" };
    expectedException.expect(CmdLineException.class);
    expectedException.expectMessage("\"--invalid\" is not a valid option");
    testWithArgs(args);
  }

  @Test
  public void testValidOptions() throws CmdLineException {
    String[] args = new String[] { "--no-cache", "//some/target" };
    String[] expectedArgs = new String[]{"//some/target"};
    assertArrayEquals(expectedArgs, testWithArgs(args));
  }

  @Test
  public void testDoubleDash1() throws CmdLineException {
    String[] args = new String[] { "--", "--invalid", "//some/target", "arg" };
    String[] expectedArgs = new String[] { "--invalid", "//some/target", "arg" };
    assertArrayEquals(expectedArgs, testWithArgs(args));
  }

  @Test
  public void testDoubleDash2() throws CmdLineException {
    String[] args = new String[] { "--", "//some/target", "--invalid", "arg" };
    String[] expectedArgs = new String[] { "//some/target", "--invalid", "arg" };
    assertArrayEquals(expectedArgs, testWithArgs(args));
  }

  @Test
  public void testDoubleDash3() throws CmdLineException {
    String[] args = new String[] {
      "//some/target", "arg1", "--", "--opt1", "something", "--opt2", "something"
    };
    String[] expectedArgs = new String[] {
      "//some/target", "arg1", "--opt1", "something", "--opt2", "something"
    };
    assertArrayEquals(expectedArgs, testWithArgs(args));
  }

  @Test
  public void testDoubleDash4() throws CmdLineException {
    String[] args = new String[] {
      "//some/target", "arg1", "--opt1", "something", "--", "--opt2", "something"
    };
    expectedException.expect(CmdLineException.class);
    expectedException.expectMessage("\"--opt1\" is not a valid option");
    testWithArgs(args);
  }
}
