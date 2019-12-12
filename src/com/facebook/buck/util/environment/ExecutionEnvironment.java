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

package com.facebook.buck.util.environment;

import java.util.Optional;

public interface ExecutionEnvironment {

  /** @return The current hostname or 'unknown' */
  String getHostname();

  /** @return The current username or 'unknown' */
  String getUsername();

  /** @return The number of cores on this machine. */
  int getAvailableCores();

  /** @return The amount of system memory on this machine in bytes. */
  long getTotalMemory();

  /** @return The current operating system. */
  Platform getPlatform();

  /** @return Whether an ethernet interface is available and enabled. */
  Network getLikelyActiveNetwork();

  /** @return The SSID of the current wifi network if it can be determined. */
  Optional<String> getWifiSsid();

  /** Gets the environment variable indicated by the specified key. */
  Optional<String> getenv(String key);
}
