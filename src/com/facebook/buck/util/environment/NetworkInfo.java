/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.util.environment;

import java.util.Optional;

public final class NetworkInfo {
  // Buck's own integration tests will run with this system property
  // set to false.
  //
  // Otherwise, we would need to add libjcocoa.dylib to
  // java.library.path, which could interfere with external Java
  // tests' own C library dependencies.
  private static final boolean ENABLE_OBJC = Boolean.getBoolean("buck.enable_objc");

  private NetworkInfo() {}

  public static Network getLikelyActiveNetwork() {
    if (ENABLE_OBJC) {
      return MacNetworkConfiguration.getLikelyActiveNetwork();
    }
    return new Network(NetworkMedium.UNKNOWN);
  }

  public static Optional<String> getWifiSsid() {
    // TODO(royw): Support Linux and Windows.
    if (ENABLE_OBJC) {
      return MacWifiSsidFinder.findCurrentSsid();
    }
    return Optional.empty();
  }
}
