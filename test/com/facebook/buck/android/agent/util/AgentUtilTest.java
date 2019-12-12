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

package com.facebook.buck.android.agent.util;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.testutil.integration.TestDataHelper;
import java.nio.file.Path;
import org.junit.Test;

public class AgentUtilTest {
  @Test
  public void testGetJarSignature() throws Exception {
    Path testDataDir = TestDataHelper.getTestDataDirectory(this);
    Path testJar = testDataDir.resolve("example.jar");
    String jarSignature = AgentUtil.getJarSignature(testJar.toAbsolutePath().toString());
    assertEquals("JB2+Jt7N6wdguWfvzaM3cJiisTM=", jarSignature);
  }
}
