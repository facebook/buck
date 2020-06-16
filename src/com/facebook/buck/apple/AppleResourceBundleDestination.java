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

/**
 * Abstraction of a place in a resulting bundle where resource will be copied. Actual value of path
 * relative to bundle root depends on a platform. Resource stands for `apple_resource` content,
 * which means this class is exposed to the end user.
 */
public enum AppleResourceBundleDestination {
  RESOURCES,
  FRAMEWORKS,
  EXECUTABLES,
  PLUGINS,
  XPCSERVICES,
  ;

  public static AppleResourceBundleDestination defaultValue() {
    return RESOURCES;
  }

  /** Returns corresponding non-specific bundle destination */
  public AppleBundleDestination asGenericDestination() {
    switch (this) {
      case RESOURCES:
        return AppleBundleDestination.RESOURCES;
      case FRAMEWORKS:
        return AppleBundleDestination.FRAMEWORKS;
      case EXECUTABLES:
        return AppleBundleDestination.EXECUTABLES;
      case PLUGINS:
        return AppleBundleDestination.PLUGINS;
      case XPCSERVICES:
        return AppleBundleDestination.XPCSERVICES;
    }
    throw new IllegalStateException("Unhandled AppleResourceBundleDestination " + this);
  }
}
