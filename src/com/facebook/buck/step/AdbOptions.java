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

package com.facebook.buck.step;

public class AdbOptions {

  public static final String MULTI_INSTALL_MODE_SHORT_ARG = "-x";

  private int adbThreadCount;
  private boolean multiInstallMode;

  public AdbOptions() {
    this(0, false);
  }

  public AdbOptions(
      int adbThreadCount,
      boolean multiInstallMode) {
    this.adbThreadCount = adbThreadCount;
    this.multiInstallMode = multiInstallMode;
  }

  public int getAdbThreadCount() {
    return adbThreadCount;
  }

  public boolean isMultiInstallModeEnabled() {
    return multiInstallMode;
  }

}
