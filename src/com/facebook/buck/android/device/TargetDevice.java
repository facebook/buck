/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.android.device;

import java.util.Optional;

/** Represents information about the device we're targeting. */
public class TargetDevice {

  private final Type type;
  private final Optional<String> identifier;

  public enum Type {
    REAL_DEVICE,
    EMULATOR,
    BY_SERIAL
  }

  public TargetDevice(Type type, Optional<String> identifier) {
    this.type = type;
    this.identifier = identifier;
  }

  public boolean isEmulator() {
    return type == Type.EMULATOR;
  }

  public Optional<String> getIdentifier() {
    return identifier;
  }
}
