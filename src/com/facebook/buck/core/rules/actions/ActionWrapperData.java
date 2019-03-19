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
package com.facebook.buck.core.rules.actions;

import org.immutables.value.Value;
import org.immutables.value.Value.Style.ImplementationVisibility;

/**
 * Implementation of {@link ActionAnalysisData} that just holds an {@link Action}. This mimics the
 * effect of having a deferred
 */
@Value.Immutable(builder = false, copy = false)
@Value.Style(visibility = ImplementationVisibility.PACKAGE)
public abstract class ActionWrapperData implements ActionAnalysisData {

  @Override
  @Value.Parameter
  public abstract ActionAnalysisDataKey getKey();

  /** @return the {@link Action} this wraps */
  @Value.Parameter
  public abstract Action getAction();
}
