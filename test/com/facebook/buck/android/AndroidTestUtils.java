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
package com.facebook.buck.android;

import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.core.toolchain.tool.impl.testutil.SimpleTool;
import com.facebook.buck.core.toolchain.toolprovider.impl.ConstantToolProvider;
import java.nio.file.Paths;
import java.util.Collections;

class AndroidTestUtils {

  private AndroidTestUtils() {}

  static AndroidPlatformTarget createAndroidPlatformTarget() {
    return AndroidPlatformTarget.of(
        "android",
        Paths.get(""),
        Collections.emptyList(),
        () -> new SimpleTool(""),
        new ConstantToolProvider(new SimpleTool("")),
        Paths.get(""),
        Paths.get(""),
        Paths.get(""),
        Paths.get("/usr/bin/dx"),
        Paths.get(""),
        Paths.get(""),
        Paths.get(""),
        Paths.get(""));
  }
}
