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

import com.facebook.buck.core.starlark.rule.attr.Attribute;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.rules.coercer.CoerceFailedException;
import com.facebook.buck.rules.coercer.TypeCoercer;
import com.google.common.reflect.TypeToken;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;
import java.util.List;

/** Class that represents a String attribute to a user defined rule */
@BuckStyleValue
public abstract class StringAttribute extends Attribute<String> {

  private static final TypeCoercer<?, String> coercer =
      TypeCoercerFactoryForStarlark.typeCoercerForType(TypeToken.of(String.class));

  @Override
  public abstract String getPreCoercionDefaultValue();

  @Override
  public abstract String getDoc();

  @Override
  public abstract boolean getMandatory();

  /** The list of values that are acceptable. If empty, allow any values */
  abstract List<String> getValues();

  @Override
  public void repr(SkylarkPrinter printer) {
    printer.append("<attr.string>");
  }

  @Override
  public TypeCoercer<?, String> getTypeCoercer() {
    return coercer;
  }

  @Override
  protected void validateCoercedValue(String value) throws CoerceFailedException {
    validateValueInList(getValues(), value);
  }

  public static StringAttribute of(
      String preCoercionDefaultValue, String doc, boolean mandatory, List<String> values) {
    return ImmutableStringAttribute.of(preCoercionDefaultValue, doc, mandatory, values);
  }
}
