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

package com.facebook.buck.apple.simulator;

import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.immutables.value.Value;

/** Object that represents an Apple simulator or a physical device. Used with idb. */
@BuckStyleValue
@JsonDeserialize(as = ImmutableAppleDevice.class)
public interface AppleDevice {

  /** String that represents the name of the device (i.e., "iPhone X", "iPad Air", etc) */
  String getName();

  /** String that represents the udid of the device (its identifier) */
  String getUdid();

  /** String that represents the state of the device (i.e., "Booted", "Shutdown", etc) */
  String getState();

  /** String that represents the type of the device ("simulator" or "device") */
  String getType();

  /** String that represents the iOS version running on the device (i.e., "iOS 12.4", etc) */
  String getOs_version();

  /** String that represents the architecture of the device (i.e., "x86_64", etc) */
  String getArchitecture();

  /** String that represents the host of the device */
  @Value.Derived
  default String getHost() {
    return "";
  }

  /** String that represents the port of the device */
  @Value.Derived
  default int getPort() {
    return 0;
  }

  /** String that indicates if the device is local or remote ("true" or "false") */
  @Value.Derived
  default boolean getIs_local() {
    return true;
  }

  static AppleDevice of(
      String name, String udid, String state, String type, String os_version, String architecture) {
    return ImmutableAppleDevice.of(name, udid, state, type, os_version, architecture);
  }
}
