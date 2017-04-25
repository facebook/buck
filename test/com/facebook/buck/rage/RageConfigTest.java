/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.rage;

import static org.junit.Assert.assertThat;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.timing.DefaultClock;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.BeforeClass;
import org.junit.Test;

public class RageConfigTest {

  private static Clock clock;
  private static BuckEventBus eventBus;

  @BeforeClass
  public static void setUp() {
    clock = new DefaultClock();
    eventBus = BuckEventBusFactory.newInstance(clock);
  }

  @Test
  public void testEmpty() {
    BuckConfig buckConfig = FakeBuckConfig.builder().build();
    RageConfig config = RageConfig.of(buckConfig);
    assertThat(config.getReportUploadPath(), Matchers.equalTo(RageConfig.UPLOAD_PATH));
    assertThat(
        config.getFrontendConfig().get().tryCreatingClientSideSlb(clock, eventBus),
        Matchers.equalTo(Optional.empty()));
  }

  @Test
  public void testUploadConfigs() {
    String testPath = "rage/upload/test";
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    "rage",
                    ImmutableMap.of(
                        "report_upload_path",
                        testPath,
                        "slb_server_pool",
                        "http://buck-frontend-dual.05.atn1.facebook.com")))
            .build();
    RageConfig config = RageConfig.of(buckConfig);
    assertThat(config.getReportUploadPath(), Matchers.equalTo(testPath));
    assertThat(
        config.getFrontendConfig().get().tryCreatingClientSideSlb(clock, eventBus),
        Matchers.not(Matchers.equalTo(Optional.empty())));
  }
}
