/*
 * Copyright 2013-present Facebook, Inc.
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

import com.facebook.buck.testutil.TestConsole;
import com.google.common.base.Optional;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

/**
 * Unit test for {@link Command}.
 */
public class CommandTest {

  /**
   * Testing the fuzzy command matching feature in {@link Command#getCommandForName}.
   */
  @Test
  public void testFuzzyCommandMatch() {
    TestConsole console;
    console = new TestConsole();

    assertEquals(Optional.of(Command.BUILD), Command.getCommandForName("buil", console));
    assertEquals(Optional.of(Command.BUILD), Command.getCommandForName("biuld", console));
    assertEquals(Optional.of(Command.AUDIT), Command.getCommandForName("audir", console));
    assertEquals(Optional.of(Command.AUDIT), Command.getCommandForName("auditt", console));
    assertEquals(Optional.of(Command.TEST), Command.getCommandForName("test", console));
    assertEquals(Optional.of(Command.TEST), Command.getCommandForName("twst", console));
    assertEquals(Optional.of(Command.TEST), Command.getCommandForName("tset", console));
    assertEquals(Optional.of(Command.INSTALL), Command.getCommandForName("imstall", console));
    assertEquals(Optional.of(Command.INSTALL), Command.getCommandForName("sintall", console));
    assertEquals(Optional.of(Command.INSTALL), Command.getCommandForName("sintalle", console));
    assertEquals(Optional.of(Command.TARGETS), Command.getCommandForName("tragets", console));
    assertEquals(Optional.of(Command.TARGETS), Command.getCommandForName("taegers", console));
    assertEquals(
        "'yyyyyyy' shouldn't match any current command.", 
        Optional.absent(), 
        Command.getCommandForName("yyyyyyy", console));

    // Boundary cases
    assertEquals(
        "'unsintakk' is of distance 4 to the closest command 'uninstall' since\n" +
        "4 / length('uninstall') = 4 / 9 is smaller than Coomand.MAX_ERROR_RATIO (0.5),\n" + 
        "we expect it matches uninstall.\n",
        Optional.of(Command.UNINSTALL), 
        Command.getCommandForName("unsintakk", console));
    assertEquals(
        "'insatkk' is of distance 4 to the closest command 'install' since\n" +
        "4 / length('install') = 4 / 7 is larger than Coomand.MAX_ERROR_RATIO (0.5),\n" +
        "we expect Optional.absent() gets returned.\n",
        Optional.absent(), 
        Command.getCommandForName("insatkk", console));
    assertEquals(
        "'atrgest' is of distance 4 to the closest command 'targets' since\n" +
        "4 / length('targets') = 4 / 7 is larger than Coomand.MAX_ERROR_RATIO (0.5),\n" +
        "we expect Optional.absent() gets returned.\n",
        Optional.absent(), 
        Command.getCommandForName("atrgest", console));
    assertEquals(
        "'unsintskk' is of distance 5 to the closest command 'uninstall' since\n" +
        "5 / length('uninstall') = 5 / 9 is larger than Coomand.MAX_ERROR_RATIO (0.5),\n" + 
        "we expect Optional.absent() gets returned.\n",
        Optional.absent(), 
        Command.getCommandForName("unsintskk", console));
  }
}
