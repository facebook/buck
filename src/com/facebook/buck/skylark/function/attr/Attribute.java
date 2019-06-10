/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.skylark.function.attr;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.coercer.CoerceFailedException;
import com.facebook.buck.rules.coercer.TypeCoercer;
import java.nio.file.Path;
import java.util.Optional;

/** Representation of a parameter of a user defined rule */
abstract class Attribute<CoercedType> implements AttributeHolder {

  @Override
  public Attribute<?> getAttribute() {
    return this;
  }

  /** The default value to use if no value is provided */
  abstract CoercedType getDefaultValue();

  /** The docstring to use for this attribute */
  abstract String getDoc();

  /** Whether this attribute is mandatory or not */
  abstract boolean getMandatory();

  /**
   * The type coercer to use to convert raw values from the parser into something usable internally.
   * This coercer also performs validation
   */
  protected abstract TypeCoercer<CoercedType> getMandatoryTypeCoercer();

  /**
   * The type coercer to use to convert raw values from the parser into something usable internally
   * if the value is not mandatory. This coercer also performs validation
   */
  protected abstract TypeCoercer<Optional<CoercedType>> getOptionalTypeCoercer();

  /**
   * Validates values post-coercion to ensure other properties besides 'type' are valid
   *
   * @param value The value to check
   * @throws CoerceFailedException if the value is invalid (e.g. not in a list of pre-approved
   *     values)
   */
  protected void validateCoercedValue(CoercedType value) throws CoerceFailedException {}

  CoercedType getValue(
      CellPathResolver cellRoots,
      ProjectFilesystem projectFilesystem,
      Path pathRelativeToProjectRoot,
      TargetConfiguration targetConfiguration,
      Object value)
      throws CoerceFailedException {
    CoercedType coercedValue =
        getMandatoryTypeCoercer()
            .coerce(
                cellRoots,
                projectFilesystem,
                pathRelativeToProjectRoot,
                targetConfiguration,
                value);
    validateCoercedValue(coercedValue);
    return coercedValue;
  }

  Optional<CoercedType> getOptionalValue(
      CellPathResolver cellRoots,
      ProjectFilesystem projectFilesystem,
      Path pathRelativeToProjectRoot,
      TargetConfiguration targetConfiguration,
      Object value)
      throws CoerceFailedException {
    Optional<CoercedType> coercedValue =
        getOptionalTypeCoercer()
            .coerce(
                cellRoots,
                projectFilesystem,
                pathRelativeToProjectRoot,
                targetConfiguration,
                value);
    if (coercedValue.isPresent()) {
      validateCoercedValue(coercedValue.get());
    }
    return coercedValue;
  }
}
