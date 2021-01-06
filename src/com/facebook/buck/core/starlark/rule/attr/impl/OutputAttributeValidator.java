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

package com.facebook.buck.core.starlark.rule.attr.impl;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.rules.actions.ActionRegistry;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.devtools.build.lib.events.Location;

/**
 * Simple container class for shared logic between {@link OutputAttribute} and {@link
 * OutputListAttribute}
 */
class OutputAttributeValidator {
  private OutputAttributeValidator() {}

  /**
   * Validates that a coerced value is a string, and registers it with {@code registry}
   *
   * @param coercedValue the value that came from {@link
   *     OutputAttribute#getValue(com.facebook.buck.core.cell.nameresolver.CellNameResolver,
   *     ProjectFilesystem, ForwardRelativePath, TargetConfiguration, TargetConfiguration, Object)}
   *     or {@link
   *     OutputListAttribute#getValue(com.facebook.buck.core.cell.nameresolver.CellNameResolver,
   *     ProjectFilesystem, ForwardRelativePath, TargetConfiguration, TargetConfiguration, Object)}
   * @param registry the registry to declare artifacts against
   * @return the declared artifact
   * @throws IllegalArgumentException if {@code coercedValue} is not a {@link String}
   */
  static Artifact validateAndRegisterArtifact(Object coercedValue, ActionRegistry registry) {
    if (!(coercedValue instanceof String)) {
      throw new IllegalArgumentException(String.format("Value %s must be a String", coercedValue));
    }
    // TODO(pjameson): pass the location of the UDR invocation all the way down to the coercer
    return registry.declareArtifact((String) coercedValue, Location.BUILTIN);
  }
}
