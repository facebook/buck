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

package com.facebook.buck.rules.coercer;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import java.nio.file.Path;
import java.util.OptionalInt;

/** Converts optional integer values into {@link OptionalInt}. */
public class OptionalIntTypeCoercer extends LeafTypeCoercer<OptionalInt> {

  @Override
  public Class<OptionalInt> getOutputClass() {
    return OptionalInt.class;
  }

  @Override
  public OptionalInt coerce(
      CellPathResolver cellRoots,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      TargetConfiguration targetConfiguration,
      Object object)
      throws CoerceFailedException {
    if (object == null) {
      return OptionalInt.empty();
    }
    if (object instanceof Integer) {
      return OptionalInt.of((int) object);
    }
    if (object instanceof Long) {
      Long longValue = (Long) object;
      if (longValue > Integer.MAX_VALUE) {
        throw CoerceFailedException.simple(
            object,
            getOutputClass(),
            String.format(
                "%s is greater than the maximum integer value %s", longValue, Integer.MAX_VALUE));
      }
      return OptionalInt.of(longValue.intValue());
    }
    throw CoerceFailedException.simple(object, getOutputClass());
  }
}
