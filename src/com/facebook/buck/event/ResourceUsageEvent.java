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

package com.facebook.buck.event;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.util.memory.ResourceUsage;

/**
 * Event fired whenever a ShellStep is executed indicating the {@link ResourceUsage} of the process
 * spawned by the shell step's executor.
 */
public class ResourceUsageEvent extends AbstractBuckEvent {
  private final BuildTarget buildTarget;
  private final ResourceUsage resourceUsage;

  public ResourceUsageEvent(BuildTarget buildTarget, ResourceUsage resourceUsage) {
    super(EventKey.unique());
    this.buildTarget = buildTarget;
    this.resourceUsage = resourceUsage;
  }

  /** @return The BuildTarget to which this process launch was contributing to. */
  public BuildTarget getBuildTarget() {
    return buildTarget;
  }

  /** @return The ResourceUsage of the launched process. */
  public ResourceUsage getResourceUsage() {
    return resourceUsage;
  }

  @Override
  protected String getValueString() {
    return buildTarget.toStringWithConfiguration();
  }

  @Override
  public String getEventName() {
    return "ResourceUsageEvent";
  }
}
