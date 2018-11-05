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

package com.facebook.buck.core.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.BuckConfigTestUtils;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.concurrent.ResourceAmounts;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

public class ResourcesConfigTest {

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Test
  public void testGettingResourceAmountsPerRuleType() throws IOException {
    Reader reader =
        new StringReader(
            Joiner.on('\n')
                .join("[resources_per_rule]", "some_rule = 1, 20, 3, 4", "other_rule = 4,30,2,1"));
    BuckConfig config = BuckConfigTestUtils.createWithDefaultFilesystem(temporaryFolder, reader);
    ImmutableMap<String, ResourceAmounts> result =
        config.getView(ResourcesConfig.class).getResourceAmountsPerRuleType();
    assertEquals(
        ImmutableMap.of(
            "some_rule", ResourceAmounts.of(1, 20, 3, 4),
            "other_rule", ResourceAmounts.of(4, 30, 2, 1)),
        result);
  }

  @Test
  public void testInvalidResourceAmountsConfiguration() throws IOException {
    Reader reader =
        new StringReader(
            Joiner.on('\n')
                .join(
                    "[resources_per_rule]",
                    "some_rule = 1, 20, 3, 4",
                    "other_rule = 4,30,2,1",
                    "wrong_config = 1,2,3,4,5,6,7,8,9,0"));
    BuckConfig config = BuckConfigTestUtils.createWithDefaultFilesystem(temporaryFolder, reader);
    try {
      config.getView(ResourcesConfig.class).getResourceAmountsPerRuleType();
    } catch (IllegalArgumentException e) {
      assertThat(
          e.getMessage(),
          Matchers.containsString(
              "Buck config entry [resources_per_rule].wrong_config"
                  + " contains 10 values, but expected to contain 4 values in the following order: "
                  + "cpu, memory, disk_io, network_io"));
      return;
    }
    assertThat("IllegalArgumentException should be thrown", Matchers.equalTo(""));
  }
}
