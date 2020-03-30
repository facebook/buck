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

package com.facebook.buck.rules.modern.builders;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.EventKey;

/** Tracks events related to {@link HybridLocalStrategy}. */
public abstract class HybridLocalEvent extends AbstractBuckEvent {
  protected HybridLocalEvent() {
    super(EventKey.unique());
  }

  public static Stolen createStolen(BuildTarget buildTarget) {
    return new Stolen(buildTarget);
  }

  @Override
  public String getEventName() {
    return getClass().getSimpleName();
  }

  /** When the HybridLocalStrategy steals a delegate strategy and starts processing it locally. */
  public static class Stolen extends HybridLocalEvent {
    private final BuildTarget buildTarget;

    private Stolen(BuildTarget buildTarget) {
      this.buildTarget = buildTarget;
    }

    public BuildTarget getBuildTarget() {
      return buildTarget;
    }

    @Override
    protected String getValueString() {
      return String.format("BuildTarget=[%s]", buildTarget.getFullyQualifiedName());
    }
  }
}
