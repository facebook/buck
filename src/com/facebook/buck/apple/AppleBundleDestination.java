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

package com.facebook.buck.apple;

import java.nio.file.Path;

/**
 * Abstraction of a place in a resulting bundle where resource will be copied. Actual value of path
 * relative to bundle root depends on a platform.
 */
public enum AppleBundleDestination {
  RESOURCES,
  FRAMEWORKS,
  EXECUTABLES,
  PLUGINS,
  XPCSERVICES;

  public static AppleBundleDestination defaultValue() {
    return RESOURCES;
  }

  /**
   * @param destinations Platform-specific set of concrete path values in a bundle.
   * @return Value of path relative to bundle root where resource will be copied.
   */
  public Path getPath(AppleBundleDestinations destinations) {
    switch (this) {
      case RESOURCES:
        return destinations.getResourcesPath();
      case EXECUTABLES:
        return destinations.getExecutablesPath();
      case FRAMEWORKS:
        return destinations.getFrameworksPath();
      case PLUGINS:
        return destinations.getPlugInsPath();
      case XPCSERVICES:
        return destinations.getXPCServicesPath();
      default:
        throw new IllegalStateException("Unhandled AppleBundleDestination " + this);
    }
  }
}
