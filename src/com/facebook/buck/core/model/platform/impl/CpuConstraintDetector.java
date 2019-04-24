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
import com.facebook.buck.util.environment.Architecture;

/**
 * A constraint detector that matches the constraint value to the host CPU.
 *
 * <p>See {@link Architecture} for list of recognized CPU types.
 */
public class CpuConstraintDetector implements HostConstraintDetector {

  private final Architecture hostCpuArchitecture;

  public CpuConstraintDetector(Architecture hostCpuArchitecture) {
    this.hostCpuArchitecture = hostCpuArchitecture;
  }

  public CpuConstraintDetector() {
    this(Architecture.detect());
  }

  @Override
  public boolean matchesHost(ConstraintValue constraintValue) {
    return hostCpuArchitecture
        == Architecture.fromName(constraintValue.getBuildTarget().getShortName());
  }
}
