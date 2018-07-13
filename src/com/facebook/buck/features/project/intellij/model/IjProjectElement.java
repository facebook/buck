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

package com.facebook.buck.features.project.intellij.model;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.features.project.intellij.IjDependencyListBuilder;
import com.google.common.collect.ImmutableSet;

/** Common interface shared between {@link IjModule} and {@link IjLibrary}. */
public interface IjProjectElement {

  /** @return unique string identifying the element. This will be used by modules to refer to it. */
  String getName();

  /** @return set of targets this element corresponds to in the IntelliJ project. */
  ImmutableSet<BuildTarget> getTargets();

  void addAsDependency(
      DependencyType dependencyType, IjDependencyListBuilder dependencyListBuilder);
}
