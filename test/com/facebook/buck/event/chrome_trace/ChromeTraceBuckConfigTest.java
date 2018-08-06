/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.event.chrome_trace;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.testutil.TemporaryPaths;
import org.junit.Rule;
import org.junit.Test;

public class ChromeTraceBuckConfigTest {

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Test
  public void testGetMaxTraces() {
    assertEquals(25, ChromeTraceBuckConfig.of(FakeBuckConfig.builder().build()).getMaxTraces());

    ChromeTraceBuckConfig config =
        ChromeTraceBuckConfig.of(
            FakeBuckConfig.builder().setSections("[log]", "max_traces = 42").build());
    assertEquals(42, config.getMaxTraces());
  }
}
