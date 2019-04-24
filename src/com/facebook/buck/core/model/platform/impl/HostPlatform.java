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

import com.facebook.buck.core.model.platform.ConstraintSetting;
import com.facebook.buck.core.model.platform.ConstraintValue;
import com.facebook.buck.core.model.platform.HostConstraintDetector;
import com.facebook.buck.core.model.platform.Platform;
import java.util.Collection;
import java.util.Optional;

/**
 * A special type of {@link Platform} that represents the host OS executing the build.
 *
 * <p>This class should be used when automatic matching of constraints is needed. A host platform
 * can also be defined by explicitly listing all constraints it consists of, {@link
 * ConstraintBasedPlatform} can be used to represent such platform. In cases when the set of
 * constraints for a host platform is not provided for a build there needs to be a way to check if a
 * given constraint matches the host OS, this is achieved by using constraint settings with {@link
 * HostConstraintDetector}.
 *
 * <p>This implementation considers constraint settings without detectors as failing the check.
 */
public class HostPlatform implements Platform {

  public static final HostPlatform INSTANCE = new HostPlatform();

  private final int hashCode = HostPlatform.class.getName().hashCode();

  private HostPlatform() {}

  @Override
  public boolean matchesAll(Collection<ConstraintValue> constraintValues) {
    for (ConstraintValue constraintValue : constraintValues) {
      ConstraintSetting constraintSetting = constraintValue.getConstraintSetting();

      Optional<HostConstraintDetector> constraintDetector =
          constraintSetting.getHostConstraintDetector();

      if (!constraintDetector.isPresent()
          || !constraintDetector.get().matchesHost(constraintValue)) {
        return false;
      }
    }

    return true;
  }

  @Override
  public String toString() {
    return "HostPlatform";
  }

  @Override
  public int hashCode() {
    return hashCode;
  }

  @Override
  public boolean equals(Object obj) {
    return obj instanceof HostPlatform;
  }
}
