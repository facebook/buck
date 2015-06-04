/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.apple.simulator;

import com.facebook.buck.util.immutables.BuckStyleImmutable;

import java.util.Set;

import org.immutables.value.Value;

/**
 * Immutable value type containing metadata about an Apple simulator.
 */
@Value.Immutable
@BuckStyleImmutable
interface AbstractAppleSimulatorProfile {
  /**
   * Set of integers corresponding to values in {@link AppleProductFamilyID}
   * describing which Apple product families this simulator supports (i.e.,
   * iPhone, iPad, Apple Watch, etc.)
   *
   * We don't directly return {@link AppleProductFamilyID} here since new
   * identifiers are introduced over time, and we don't want to lose
   * the information at this level.
   */
  Set<Integer> getSupportedProductFamilyIDs();

  /**
   * Set of strings containing the architectures supported by this
   * simulator (i.e., i386, x86_64, etc.)
   */
  Set<String> getSupportedArchitectures();
}
