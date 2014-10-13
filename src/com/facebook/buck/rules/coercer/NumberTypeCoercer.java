/*
 * Copyright 2013-present Facebook, Inc.
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
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Preconditions;

import java.nio.file.Path;

/**
 * Coerces numbers with rounding/truncation/extension.
 */
public class NumberTypeCoercer<T extends Number> extends LeafTypeCoercer<T> {
  private final Class<T> type;

  NumberTypeCoercer(Class<T> type) {
    this.type = Preconditions.checkNotNull(type);
  }

  @Override
  public Class<T> getOutputClass() {
    return type;
  }

  @Override
  public T coerce(
      BuildTargetParser buildTargetParser,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      Object object)
      throws CoerceFailedException {
    if (object instanceof Number) {
      @SuppressWarnings("unchecked")
      T castedNumber = (T) castNumber((Number) object);
      return castedNumber;
    }
    throw CoerceFailedException.simple(object, getOutputClass());
  }

  /**
   * Cast the number to the correct subtype.
   */
  private Number castNumber(Number number) {
    if (Integer.class.equals(type)) {
      return number.intValue();
    } else if (Long.class.equals(type)) {
      return number.longValue();
    } else if (Short.class.equals(type)) {
      return number.shortValue();
    } else if (Byte.class.equals(type)) {
      return number.byteValue();
    } else if (Double.class.equals(type)) {
      return number.doubleValue();
    } else if (Float.class.equals(type)) {
      return number.floatValue();
    } else {
      throw new RuntimeException("cannot handle numeric type: " + type);
    }
  }
}
