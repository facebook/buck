/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.zaxxer.nuprocess.NuProcess;
import org.junit.Before;
import org.junit.Test;

public class ProcessHelperTest {

  private ProcessHelper processHelper;

  @Before
  public void setUp() {
    processHelper = ProcessHelper.getInstance();
  }

  @Test
  public void testGetPidDoesNotThrow() {
    processHelper.getPid();
  }

  @Test(expected = IllegalArgumentException.class)
  public void testGetPidThrowsForUnknownProcessClass() {
    processHelper.getPid(new Object());
  }

  @Test
  public void testGetPidNuProcess() {
    NuProcess nuProcess = new FakeNuProcess(1234);
    assertEquals(Long.valueOf(1234), processHelper.getPid(nuProcess));
  }

  @Test
  public void testGetPidJavaProcess() {
    // There are multiple platform-specific implementations of {@link Process}, so here we only
    // test that the method doesn't throw.
    Process process = new FakeProcess(0);
    assertNull(processHelper.getPid(process));
  }

  @Test
  public void testHasNuProcessFinished() {
    FakeNuProcess nuProcess = new FakeNuProcess(1234);
    assertFalse(processHelper.hasProcessFinished(nuProcess));
    nuProcess.finish(0);
    assertTrue(processHelper.hasProcessFinished(nuProcess));
  }

  @Test
  public void testHasJavaProcessFinished() throws Exception {
    Process process = new FakeProcess(42);
    assertFalse(processHelper.hasProcessFinished(process));
    process.waitFor();
    assertTrue(processHelper.hasProcessFinished(process));
  }

  @Test
  public void testGetProcessResourceConsumptionDoesNotThrow() {
    assertNull(processHelper.getProcessResourceConsumption(-100));
  }
}
