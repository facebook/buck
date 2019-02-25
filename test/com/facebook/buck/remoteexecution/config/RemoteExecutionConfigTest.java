/*
 * Copyright 2019-present Facebook, Inc.
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

package com.facebook.buck.remoteexecution.config;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.remoteexecution.proto.RESessionID;
import org.junit.Assert;
import org.junit.Test;

public class RemoteExecutionConfigTest {

  private static final RESessionID RE_SESSION_ID =
      RESessionID.newBuilder().setId("topspin").build();

  @Test
  public void testGetDebugURLStringWithoutFormatString() {
    RemoteExecutionConfig config = getConfig("");
    Assert.assertEquals("topspin", config.getDebugURLString(RE_SESSION_ID));
  }

  @Test
  public void testGetDebugURLStringWithFormatStringNotContainingMarker() {
    RemoteExecutionConfig config = getConfig("https://localhost/test?blah=");
    Assert.assertEquals("https://localhost/test?blah=", config.getDebugURLString(RE_SESSION_ID));
  }

  @Test
  public void testGetDebugURLStringWithFormatString() {
    RemoteExecutionConfig config = getConfig("https://localhost/test?blah={id}");
    Assert.assertEquals(
        "https://localhost/test?blah=topspin", config.getDebugURLString(RE_SESSION_ID));
  }

  @Test
  public void testValidationOfCertificateFiles() {
    BuckConfig config =
        FakeBuckConfig.builder().setSections("[remoteexecution]", "ca=does_not_exist").build();
    RemoteExecutionConfig reConfig = config.getView(RemoteExecutionConfig.class);
    try {
      reConfig.validateCertificatesOrThrow();
      Assert.fail("Should've thrown.");
    } catch (HumanReadableException e) {
      Assert.assertEquals(
          "Config [remoteexecution.cert] must point to a file.\n"
              + "Config [remoteexecution.key] must point to a file.\n"
              + "Config [remoteexecution.ca] points to file [does_not_exist] that does not exist.",
          e.getMessage());
    }
  }

  private RemoteExecutionConfig getConfig(String debugFormatString) {
    BuckConfig config =
        FakeBuckConfig.builder()
            .setSections("[remoteexecution]", "debug_format_string_url=" + debugFormatString)
            .build();
    return config.getView(RemoteExecutionConfig.class);
  }
}
