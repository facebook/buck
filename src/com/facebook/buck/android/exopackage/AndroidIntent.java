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

package com.facebook.buck.android.exopackage;

/** Data class for parameters to a `adb shell am start` command. */
public class AndroidIntent {

  public static final String ACTION_MAIN = "android.intent.action.MAIN";
  public static final String ACTION_VIEW = "android.intent.action.VIEW";
  public static final String CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER";

  public final String componentName;
  public final String action;
  public final String category;
  public final String dataUri;
  public final String flags;
  public final boolean waitForDebugger;

  public AndroidIntent(
      String componentName,
      String action,
      String category,
      String dataUri,
      String flags,
      boolean waitForDebugger) {
    this.componentName = componentName;
    this.action = action;
    this.category = category;
    this.dataUri = dataUri;
    this.flags = flags;
    this.waitForDebugger = waitForDebugger;
  }

  /** @return the `am start` command for this intent as a String */
  public static String getAmStartCommand(AndroidIntent intent) {
    final StringBuilder builder = new StringBuilder("am start ");
    if (intent.flags != null) {
      builder.append("-f ").append(intent.flags).append(" ");
    }
    if (intent.action != null) {
      builder.append("-a ").append(intent.action).append(" ");
    }
    if (intent.category != null) {
      builder.append("-c ").append(intent.category).append(" ");
    }
    if (intent.dataUri != null) {
      builder.append("-d ").append(intent.dataUri).append(" ");
    }
    if (intent.componentName != null) {
      builder.append("-n ").append(intent.componentName).append(" ");
    }
    if (intent.waitForDebugger) {
      builder.append("-D ");
    }
    return builder.toString();
  }
}
