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

import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.rules.coercer.CoerceFailedException;
import com.facebook.buck.rules.coercer.OptionalTypeCoercer;
import com.facebook.buck.rules.coercer.StringTypeCoercer;
import com.facebook.buck.rules.coercer.TypeCoercer;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;
import java.util.List;
import java.util.Optional;

/** Class that represents a String attribute to a user defined rule */
@BuckStyleValue
public abstract class StringAttribute extends Attribute<String> {

  private static final TypeCoercer<String> coercer = new StringTypeCoercer();
  private static final OptionalTypeCoercer<String> optionalCoercer =
      new OptionalTypeCoercer<>(coercer);

  @Override
  public abstract String getDefaultValue();

  @Override
  abstract String getDoc();

  @Override
  public abstract boolean getMandatory();

  /** The list of values that are acceptable. If empty, allow any values */
  abstract List<String> getValues();

  @Override
  public void repr(SkylarkPrinter printer) {
    printer.append("<attr.string>");
  }

  @Override
  protected TypeCoercer<String> getMandatoryTypeCoercer() {
    return coercer;
  }

  @Override
  protected TypeCoercer<Optional<String>> getOptionalTypeCoercer() {
    return optionalCoercer;
  }

  @Override
  protected void validateCoercedValue(String value) throws CoerceFailedException {
    validateValueInList(getValues(), value);
  }
}
