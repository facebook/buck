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

import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.reflect.TypeToken;
import java.util.Arrays;
import java.util.Locale;

/** Coerce a string to java enum. */
public class EnumTypeCoercer<E extends Enum<E>> extends LeafUnconfiguredOnlyCoercer<E> {
  private final Class<E> enumClass;

  public EnumTypeCoercer(Class<E> e) {
    this.enumClass = e;
  }

  @Override
  public TypeToken<E> getUnconfiguredType() {
    return TypeToken.of(enumClass);
  }

  @Override
  public E coerceToUnconfigured(
      CellNameResolver cellRoots,
      ProjectFilesystem filesystem,
      ForwardRelativePath pathRelativeToProjectRoot,
      Object object)
      throws CoerceFailedException {
    if (object instanceof String) {
      try {
        // Common case with uppercase roman enum names
        return Enum.valueOf(enumClass, ((String) object).toUpperCase(Locale.ENGLISH));
      } catch (IllegalArgumentException e) {
        // Handle lower case enum names and odd stuff like Turkish i's
        for (E value : enumClass.getEnumConstants()) {
          if (value.toString().compareToIgnoreCase((String) object) == 0) {
            return value;
          }
        }
      }
    }
    throw CoerceFailedException.simple(
        object,
        getOutputType(),
        "Allowed values: " + Arrays.toString(enumClass.getEnumConstants()));
  }
}
