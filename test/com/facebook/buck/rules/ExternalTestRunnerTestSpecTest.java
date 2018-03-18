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

package com.facebook.buck.rules;

import static org.junit.Assert.assertThat;

import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.util.json.ObjectMappers;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import org.hamcrest.Matchers;
import org.junit.Test;

public class ExternalTestRunnerTestSpecTest {

  @Test
  public void serializeToJson() throws IOException {
    String result =
        ObjectMappers.WRITER.writeValueAsString(
            ExternalTestRunnerTestSpec.builder()
                .setTarget(BuildTargetFactory.newInstance("//:target"))
                .setType("custom")
                .setLabels(ImmutableList.of("label"))
                .build());
    assertThat(
        result,
        Matchers.equalTo(
            "{\"target\":\"//:target\",\"type\":\"custom\",\"command\":[],"
                + "\"env\":{},\"labels\":[\"label\"],\"contacts\":[]}"));
  }
}
