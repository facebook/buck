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

package com.example;

import static org.testng.Assert.fail;

import java.util.logging.Logger;
import org.testng.annotations.Test;

public class TestNGLoggingTest {

  private static final Logger LOG = Logger.getLogger("LoggingTest");

  @Test
  public void passingTestWithLogMessages() {
    LOG.severe("This is an error in a passing test");
    LOG.warning("This is a warning in a passing test");
    LOG.info("This is an info message in a passing test");
    LOG.fine("This is a debug message in a passing test");
    LOG.finer("This is a verbose message in a passing test");
    LOG.finest("This is a super verbose message in a passing test");
  }

  @Test
  public void failingTestWithLogMessages() {
    LOG.severe("This is an error in a failing test");
    LOG.warning("This is a warning in a failing test");
    LOG.info("This is an info message in a failing test");
    LOG.fine("This is a debug message in a failing test");
    LOG.finer("This is a verbose message in a failing test");
    LOG.finest("This is a super verbose message in a failing test");

    fail("Intentionally failing test to get log output in a failing test");
  }
}
