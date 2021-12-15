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

package com.facebook.buck.core.toolchain.tool.impl;

import com.facebook.buck.core.rulekey.CustomFieldBehavior;
import com.facebook.buck.core.toolchain.tool.DelegatingTool;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.rules.modern.RemoteExecutionEnabled;
import com.facebook.buck.util.environment.Platform;

/** A {@link Tool} wrapper that conditionally enables remote execution. */
public class RemoteExecutionEnabledTool extends DelegatingTool {
  @CustomFieldBehavior(RemoteExecutionEnabled.class)
  private final boolean enabled;

  public RemoteExecutionEnabledTool(Tool delegate, boolean enabled) {
    super(delegate);
    this.enabled = enabled;
  }

  public static RemoteExecutionEnabledTool getEnabledOnLinuxHost(Tool tool) {
    return new RemoteExecutionEnabledTool(tool, Platform.detect() == Platform.LINUX);
  }
}
