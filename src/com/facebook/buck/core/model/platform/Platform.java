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

package com.facebook.buck.core.model.platform;

import java.util.Collection;

/**
 * A platform is defined as a set of properties (constraints).
 *
 * <p>The platform constraints can be defined in different ways but the representation should not
 * matter as long as a platform can figure out whether it's matching a set of given constraints or
 * not.
 */
public interface Platform {

  /** @return {@code true} if the current platform matches the provided constraints. */
  boolean matchesAll(Collection<ConstraintValue> constraintValues);
}
