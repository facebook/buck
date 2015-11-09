/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.simulate;

import com.facebook.buck.testutil.integration.TestDataHelper;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;

public class SimulateTimesTest {
  private static final long DEFAULT_MILLIS = 42;
  private static final String TEST_FILE = "simulate_times.json";
  private static final String KNOWN_TARGET = "//lovely/target";
  private static final String KNOWN_TIME_TYPE = "avg";

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Test
  public void testReadingFileWithExistingTimeType() throws IOException {
    SimulateTimes times = createDefaultTestInstance();
    Assert.assertTrue(times.hasMillisForTarget(KNOWN_TARGET));
  }

  @Test
  public void testCreateWithoutFile() {
    SimulateTimes times = SimulateTimes.createEmpty(DEFAULT_MILLIS);
    Assert.assertFalse(times.hasMillisForTarget(KNOWN_TARGET));
  }

  private static String getTestDataFile() throws IOException {
    Path testDataDir = TestDataHelper.getTestDataDirectory(SimulateTimesTest.class);
    return testDataDir.resolve(TEST_FILE).toString();
  }

  private static SimulateTimes createDefaultTestInstance() throws IOException {
    return SimulateTimes.createFromJsonFile(
        OBJECT_MAPPER,
        getTestDataFile(),
        KNOWN_TIME_TYPE,
        DEFAULT_MILLIS);
  }
}
