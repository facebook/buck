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

package com.facebook.buck.android;

import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableMap;
import org.junit.Test;

/** Test manifest placeholder replacement */
public class ReplaceManifestPlaceholdersStepTest {

  @Test
  public void shouldReplaceManifestPlaceholders() {
    String placeholderText =
        "<manifest>\n"
            + "    <permission\n"
            + "        android:name=\"${applicationId}.permission.C2D_MESSAGE\"\n"
            + "        android:protectionLevel=\"${custom$}\" />\n"
            + "</manifest>";
    ImmutableMap<String, String> placeholders =
        ImmutableMap.of("applicationId", "com.example", "custom$", "0x2");
    String replaced =
        ReplaceManifestPlaceholdersStep.replacePlaceholders(placeholderText, placeholders);
    String expected =
        "<manifest>\n"
            + "    <permission\n"
            + "        android:name=\"com.example.permission.C2D_MESSAGE\"\n"
            + "        android:protectionLevel=\"0x2\" />\n"
            + "</manifest>";

    assertEquals(expected, replaced);
  }
}
