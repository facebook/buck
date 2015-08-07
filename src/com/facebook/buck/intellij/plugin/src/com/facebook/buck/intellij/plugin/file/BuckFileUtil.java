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

package com.facebook.buck.intellij.plugin.file;

public final class BuckFileUtil {

  private static final String DEFAULT_BUILD_FILE = "BUCK";
  private static final String SAMPLE_BUCK_FILE =
      "# Thanks for installing Buck Plugin for IDEA!\n" +
      "android_library(\n" +
      "  name = 'bar',\n" +
      "  srcs = glob(['**/*.java']),\n" +
      "  deps = [\n" +
      "    '//android_res/com/foo/interfaces:res',\n" +
      "    '//android_res/com/foo/common/strings:res',\n" +
      "    '//android_res/com/foo/custom:res'\n" +
      "  ],\n" +
      "  visibility = [\n" +
      "    'PUBLIC',\n" +
      "  ],\n" +
      ")\n" +
      "\n" +
      "project_config(\n" +
      "  src_target = ':bar',\n" +
      ")\n";

  private BuckFileUtil() {
  }

  public static String getBuildFileName() {
    // TODO(#7908500): Read from ".buckconfig".
    return DEFAULT_BUILD_FILE;
  }

  public static String getSampleBuckFile() {
    return SAMPLE_BUCK_FILE;
  }
}
