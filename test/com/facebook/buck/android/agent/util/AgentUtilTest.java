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

package com.facebook.buck.android.agent.util;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.android.AndroidManifestReader;
import com.facebook.buck.android.DefaultAndroidManifestReader;
import com.google.common.io.ByteStreams;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;

public class AgentUtilTest {
  @Test
  public void testGetJarSignature() throws Exception {
    Path testDataDir = TestDataHelper.getTestDataDirectory(this);
    Path testJar = testDataDir.resolve("example.jar");
    String jarSignature = AgentUtil.getJarSignature(testJar.toAbsolutePath().toString());
    assertEquals("JB2+Jt7N6wdguWfvzaM3cJiisTM=", jarSignature);
  }

  @Test
  public void testConstants() throws IOException {
    AndroidManifestReader manifest =
        DefaultAndroidManifestReader.forString(
            new String(
                ByteStreams.toByteArray(
                    getClass().getResourceAsStream(
                        "/com/facebook/buck/android/agent/AndroidManifest.xml"))));

    assertEquals(AgentUtil.AGENT_PACKAGE_NAME, manifest.getPackage());
    assertEquals(AgentUtil.AGENT_VERSION_CODE, manifest.getVersionCode());

  }
}
