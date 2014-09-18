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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.google.common.base.Optional;

import org.junit.Test;

import java.io.IOException;

/**
 * Unit test for {@link Command}.
 */
public class CommandTest {

  /**
   * Testing the fuzzy command matching feature in {@link Command#parseCommandName}.
   */
  @Test
  public void testFuzzyCommandMatch() {
    assertEquals(Optional.of(Command.BUILD), Command.parseCommandName("buil").getCommand());
    assertEquals(Optional.of(Command.BUILD), Command.parseCommandName("biuld").getCommand());
    assertEquals(Optional.of(Command.AUDIT), Command.parseCommandName("audir").getCommand());
    assertEquals(Optional.of(Command.AUDIT), Command.parseCommandName("auditt").getCommand());
    assertEquals(Optional.of(Command.TEST), Command.parseCommandName("test").getCommand());
    assertEquals(Optional.of(Command.TEST), Command.parseCommandName("twst").getCommand());
    assertEquals(Optional.of(Command.TEST), Command.parseCommandName("tset").getCommand());
    assertEquals(Optional.of(Command.INSTALL), Command.parseCommandName("imstall").getCommand());
    assertEquals(Optional.of(Command.INSTALL), Command.parseCommandName("sintall").getCommand());
    assertEquals(Optional.of(Command.INSTALL), Command.parseCommandName("sintalle").getCommand());
    assertEquals(Optional.of(Command.TARGETS), Command.parseCommandName("tragets").getCommand());
    assertEquals(Optional.of(Command.TARGETS), Command.parseCommandName("taegers").getCommand());
    assertEquals(
        "'yyyyyyy' shouldn't match any current command.",
        Optional.absent(),
        Command.parseCommandName("yyyyyyy").getCommand());

    // Boundary cases
    assertEquals(
        "'unsintakk' is of distance 4 to the closest command 'uninstall' since\n" +
        "4 / length('uninstall') = 4 / 9 is smaller than Command.MAX_ERROR_RATIO (0.5),\n" +
        "we expect it matches uninstall.\n",
        Optional.of(Command.UNINSTALL),
        Command.parseCommandName("unsintakk").getCommand());
    assertEquals(
        "'insatkk' is of distance 4 to the closest command 'install' since\n" +
        "4 / length('install') = 4 / 7 is larger than Command.MAX_ERROR_RATIO (0.5),\n" +
        "we expect Optional.absent() gets returned.\n",
        Optional.absent(),
        Command.parseCommandName("insatkk").getCommand());
    assertEquals(
        "'atrgest' is of distance 4 to the closest command 'targets' since\n" +
        "4 / length('targets') = 4 / 7 is larger than Command.MAX_ERROR_RATIO (0.5),\n" +
        "we expect Optional.absent() gets returned.\n",
        Optional.absent(),
        Command.parseCommandName("atrgest").getCommand());
    assertEquals(
        "'unsintskk' is of distance 5 to the closest command 'uninstall' since\n" +
        "5 / length('uninstall') = 5 / 9 is larger than Command.MAX_ERROR_RATIO (0.5),\n" +
        "we expect Optional.absent() gets returned.\n",
        Optional.absent(),
        Command.parseCommandName("unsintskk").getCommand());
  }

  @Test
  public void commandConstructorsTakeJustCommandRunnerParams()
      throws IOException, InterruptedException {
    CommandRunnerParams params = CommandRunnerParamsForTesting.builder()
        .build();
    for (Command command : Command.values()) {
      try {
        command.getCommandRunnerClass()
            .getDeclaredConstructor(CommandRunnerParams.class)
            .newInstance(params);
      } catch (Exception e) {
        fail(String.format("%s: %s %s", command.getDeclaringClass(), e.getClass(), e.getMessage()));
      }
    }
  }

}
