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

package com.facebook.buck.rules.coercer;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.parser.buildtargetparser.BuildTargetOutputLabelParser;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import java.util.function.BiFunction;

/**
 * Coercer that coerces a build target into type T and attaches an output label, if any. The
 * resulting object is of type U.
 */
abstract class TargetWithOutputsTypeCoercer<T, U> extends LeafTypeCoercer<U> {
  private final TypeCoercer<T> coercer;

  TargetWithOutputsTypeCoercer(TypeCoercer<T> coercer) {
    this.coercer = coercer;
  }

  /** Returns the coerced build target and its associated output label, if present. */
  protected U getTargetWithOutputLabel(
      BiFunction<T, OutputLabel, U> returnTypeConstructor, Object object, CoerceParameters params)
      throws CoerceFailedException {
    if (!(object instanceof String)) {
      throw CoerceFailedException.simple(object, getOutputClass());
    }

    try {
      BuildTargetOutputLabelParser.TargetWithOutputLabel targetWithOutputLabel =
          BuildTargetOutputLabelParser.getBuildTargetNameWithOutputLabel((String) object);
      T coerced =
          coercer.coerce(
              params.getCellRoots(),
              params.getFilesystem(),
              params.getPathRelativeToProjectRoot(),
              params.getTargetConfiguration(),
              params.getHostConfiguration(),
              targetWithOutputLabel.getTargetName());
      return returnTypeConstructor.apply(coerced, targetWithOutputLabel.getOutputLabel());
    } catch (Throwable t) {
      throw new CoerceFailedException(t.getMessage(), t);
    }
  }

  /** Wrapper for values needed for perform coercion. */
  @BuckStyleValue
  abstract static class CoerceParameters {
    abstract CellPathResolver getCellRoots();

    abstract ProjectFilesystem getFilesystem();

    abstract ForwardRelativePath getPathRelativeToProjectRoot();

    abstract TargetConfiguration getTargetConfiguration();

    abstract TargetConfiguration getHostConfiguration();
  }
}
