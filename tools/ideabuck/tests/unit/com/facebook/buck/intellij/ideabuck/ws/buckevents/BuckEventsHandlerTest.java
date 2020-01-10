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

package com.facebook.buck.intellij.ideabuck.ws.buckevents;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.facebook.buck.event.external.elements.TestResultSummaryExternalInterface;
import com.facebook.buck.intellij.ideabuck.ws.buckevents.parts.TestResultsSummary;
import com.facebook.buck.test.result.type.ResultType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

public class BuckEventsHandlerTest {
  @Test
  public void testResultsSummaryKnowsResultType() throws Exception {
    final String json =
        "{\n"
            + "    \"testCaseName\": \"ok.api.ApiTest\",\n"
            + "    \"type\": \"SUCCESS\",\n"
            + "    \"time\": 3348,\n"
            + "    \"message\": null,\n"
            + "    \"stacktrace\": null,\n"
            + "    \"stdOut\": \"sup from test stdout\",\n"
            + "    \"stdErr\": null,\n"
            + "    \"testName\": \"logoutAll\"\n"
            + "}";
    final ObjectMapper objectMapper = BuckEventsHandler.createObjectMapper();
    final TestResultSummaryExternalInterface res =
        objectMapper.readValue(json, TestResultsSummary.class);
    assertFalse(res.isSuccess()); // because it is @JsonIgnore'd
    assertEquals(ResultType.SUCCESS, res.getType());
  }

  @Test
  public void testResultSummaryExternalInterfaceKnowsResultType() throws Exception {
    final String json =
        "{\n"
            + "    \"testCaseName\": \"ok.api.ApiTest\",\n"
            + "    \"type\": \"FAILURE\",\n"
            + "    \"time\": 3348,\n"
            + "    \"message\": null,\n"
            + "    \"stacktrace\": null,\n"
            + "    \"stdOut\": \"sup from test stdout\",\n"
            + "    \"stdErr\": null,\n"
            + "    \"testName\": \"logoutAll\"\n"
            + "}";
    final ObjectMapper objectMapper = BuckEventsHandler.createObjectMapper();
    final TestResultSummaryExternalInterface res =
        objectMapper.readValue(json, TestResultSummaryExternalInterface.class);
    assertFalse(res.isSuccess()); // because it is @JsonIgnore'd
    assertEquals(ResultType.FAILURE, res.getType());
  }
}
