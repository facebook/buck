/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.jvm.core;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.google.common.base.Preconditions;

/** Provides some utilities for dealing with Java abis and abi rules. */
public class JavaAbis {
  private JavaAbis() {}

  public static final Flavor CLASS_ABI_FLAVOR = InternalFlavor.of("class-abi");
  public static final Flavor SOURCE_ABI_FLAVOR = InternalFlavor.of("source-abi");
  public static final Flavor SOURCE_ONLY_ABI_FLAVOR = InternalFlavor.of("source-only-abi");
  public static final Flavor VERIFIED_SOURCE_ABI_FLAVOR = InternalFlavor.of("verified-source-abi");

  public static BuildTarget getClassAbiJar(BuildTarget libraryTarget) {
    Preconditions.checkArgument(isLibraryTarget(libraryTarget));
    return libraryTarget.withAppendedFlavors(CLASS_ABI_FLAVOR);
  }

  /** Returns whether this target is an abi target. */
  public static boolean isAbiTarget(BuildTarget target) {
    return isClassAbiTarget(target)
        || isSourceAbiTarget(target)
        || isSourceOnlyAbiTarget(target)
        || isVerifiedSourceAbiTarget(target);
  }

  public static boolean isClassAbiTarget(BuildTarget target) {
    return target.getFlavors().contains(CLASS_ABI_FLAVOR);
  }

  public static BuildTarget getSourceAbiJar(BuildTarget libraryTarget) {
    Preconditions.checkArgument(isLibraryTarget(libraryTarget));
    return libraryTarget.withAppendedFlavors(SOURCE_ABI_FLAVOR);
  }

  public static boolean isSourceAbiTarget(BuildTarget target) {
    return target.getFlavors().contains(SOURCE_ABI_FLAVOR);
  }

  public static BuildTarget getSourceOnlyAbiJar(BuildTarget libraryTarget) {
    Preconditions.checkArgument(isLibraryTarget(libraryTarget));
    return libraryTarget.withAppendedFlavors(SOURCE_ONLY_ABI_FLAVOR);
  }

  public static boolean isSourceOnlyAbiTarget(BuildTarget target) {
    return target.getFlavors().contains(SOURCE_ONLY_ABI_FLAVOR);
  }

  public static BuildTarget getVerifiedSourceAbiJar(BuildTarget libraryTarget) {
    Preconditions.checkArgument(isLibraryTarget(libraryTarget));
    return libraryTarget.withAppendedFlavors(VERIFIED_SOURCE_ABI_FLAVOR);
  }

  public static boolean isVerifiedSourceAbiTarget(BuildTarget target) {
    return target.getFlavors().contains(VERIFIED_SOURCE_ABI_FLAVOR);
  }

  public static boolean isLibraryTarget(BuildTarget target) {
    return !isAbiTarget(target);
  }

  /** Returns the library target for an abi target. */
  public static BuildTarget getLibraryTarget(BuildTarget abiTarget) {
    Preconditions.checkArgument(isAbiTarget(abiTarget));
    return abiTarget.withoutFlavors(
        CLASS_ABI_FLAVOR, SOURCE_ABI_FLAVOR, SOURCE_ONLY_ABI_FLAVOR, VERIFIED_SOURCE_ABI_FLAVOR);
  }
}
