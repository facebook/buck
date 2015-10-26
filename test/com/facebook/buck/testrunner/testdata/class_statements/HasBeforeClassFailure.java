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

import org.junit.BeforeClass;
import org.junit.Test;

public class HasBeforeClassFailure {

  @BeforeClass
  public static void setUpClass() throws InterruptedException {
    /*
     * Mocking the clock isn't possible when testing JUnitRunner, because that class is cleanly
     * implemented with minimal external dependencies.  It's even harder than that given that it is
     * invoked in a new java thread outside of the control of "buck test"'s JUnit thread.
     *
     * It's easier just to make it sleep for a bit, and verify that we had a non "<100ms" runtime.
     */
    Thread.sleep(250);
    throw new RuntimeException("BOOM!");
  }

  @Test public void shouldA() {}

  @Test public void shouldB() {}

  @Test public void shouldC() {}
}
