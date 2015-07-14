/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.apple;

import com.facebook.buck.util.immutables.BuckStyleImmutable;

import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractApplePlatform implements Comparable<AbstractApplePlatform> {
  class Name {
    public static final String IPHONEOS = "iphoneos";
    public static final String IPHONESIMULATOR = "iphonesimulator";
    public static final String WATCHOS = "watchos";
    public static final String WATCHSIMULATOR = "watchsimulator";
    public static final String MACOSX = "macosx";

    private Name() { }
  }

  /**
   * The full name of the platform. For example: {@code macosx}.
   */
  public abstract String getName();

  @Override
  public int compareTo(AbstractApplePlatform other) {
    return getName().compareTo(other.getName());
  }
}
