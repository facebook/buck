/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.core.select;

import com.facebook.buck.core.model.UnconfiguredBuildTargetView;

/**
 * A condition in <code>select</code> statements.
 *
 * <p>Such condition is referenced from keys in a <code>select</code> statement by a {@link
 * UnconfiguredBuildTargetView} which should point to an instance that implements {@link
 * ProvidesSelectable} which is used to obtain the {@link Selectable}.
 */
public interface Selectable {

  /** @return <code>true</code> if this condition matches the configuration */
  boolean matches(SelectableConfigurationContext configurationContext);

  /** @return <code>true</code> if this condition is more specialized than the given one */
  boolean refines(Selectable other);

  /** @return build target of this condition */
  UnconfiguredBuildTargetView getBuildTarget();
}
