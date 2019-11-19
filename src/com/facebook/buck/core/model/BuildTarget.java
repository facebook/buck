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

package com.facebook.buck.core.model;

import com.facebook.buck.core.exceptions.DependencyStack;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Set;
import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
public interface BuildTarget extends Comparable<BuildTarget>, DependencyStack.Element {

  UnconfiguredBuildTargetView getUnconfiguredBuildTargetView();

  UnflavoredBuildTargetView getUnflavoredBuildTarget();

  ImmutableSortedSet<Flavor> getFlavors();

  TargetConfiguration getTargetConfiguration();

  CanonicalCellName getCell();

  String getBaseName();

  CellRelativePath getCellRelativeBasePath();

  String getShortName();

  /**
   * If this build target were cell//third_party/java/guava:guava-latest, then this would return
   * "guava-latest". Note that the flavor of the target is included here.
   */
  String getShortNameAndFlavorPostfix();

  String getFlavorPostfix();

  /**
   * If this build target is cell//third_party/java/guava:guava-latest, then this would return
   * "cell//third_party/java/guava:guava-latest".
   */
  String getFullyQualifiedName();

  /**
   * If this build target is cell//third_party/java/guava:guava-latest, then this would return
   * "//third_party/java/guava:guava-latest".
   */
  String getCellRelativeName();

  boolean isFlavored();

  BuildTarget withShortName(String shortName);

  /**
   * Verifies that this build target has no flavors.
   *
   * @return this build target
   * @throws IllegalStateException if a build target has flavors
   */
  BuildTarget assertUnflavored();

  BuildTarget withoutFlavors(Set<Flavor> flavors);

  BuildTarget withoutFlavors(Flavor... flavors);

  BuildTarget withoutFlavors();

  BuildTarget withFlavors(Flavor... flavors);

  BuildTarget withFlavors(Iterable<? extends Flavor> flavors);

  BuildTarget withAppendedFlavors(Set<Flavor> flavors);

  BuildTarget withAppendedFlavors(Flavor... flavors);

  BuildTarget withUnflavoredBuildTarget(UnflavoredBuildTargetView target);
}
