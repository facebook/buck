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
package com.facebook.buck.core.model.platform.impl;

import com.facebook.buck.core.model.platform.ConstraintValue;
import com.facebook.buck.core.model.platform.HostConstraintDetector;
import com.facebook.buck.util.environment.Platform;

/**
 * A constraint detector that matches the constraint value to the host OS name.
 *
 * <p>See {@link Platform} for list of recognized OS types.
 */
public class OsConstraintDetector implements HostConstraintDetector {

  private final Platform platform;

  public OsConstraintDetector(Platform platform) {
    this.platform = platform;
  }

  public OsConstraintDetector() {
    this(Platform.detect());
  }

  @Override
  public boolean matchesHost(ConstraintValue constraintValue) {
    String name = constraintValue.getBuildTarget().getShortName();
    return platform.getCanonicalName().equals(name);
  }
}
