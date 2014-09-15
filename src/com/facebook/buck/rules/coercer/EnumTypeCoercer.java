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

package com.facebook.buck.rules.coercer;

import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.util.ProjectFilesystem;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

public class EnumTypeCoercer<E extends Enum<E>> extends LeafTypeCoercer<E> {
  private final Class<E> enumClass;

  @SuppressWarnings("unchecked")
  public EnumTypeCoercer(Class<?> e) {
    this.enumClass = (Class<E>) e;
  }

  @Override
  public Class<E> getOutputClass() {
    return enumClass;
  }

  @Override
  public E coerce(
      BuildTargetParser buildTargetParser,
      BuildRuleResolver buildRuleResolver,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      Object object) throws CoerceFailedException {
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
        getOutputClass(),
        "Allowed values: " + Arrays.toString(enumClass.getEnumConstants()));
  }
}
