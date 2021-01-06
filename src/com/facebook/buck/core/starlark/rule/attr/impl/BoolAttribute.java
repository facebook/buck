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
import com.facebook.buck.rules.coercer.TypeCoercer;
import com.google.common.reflect.TypeToken;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;

/** Class that represents an Boolean attribute to a user defined rule */
@BuckStyleValue
public abstract class BoolAttribute extends Attribute<Boolean> {

  private static final TypeCoercer<?, Boolean> coercer =
      TypeCoercerFactoryForStarlark.typeCoercerForType(TypeToken.of(Boolean.class));

  @Override
  public abstract Boolean getPreCoercionDefaultValue();

  @Override
  public abstract String getDoc();

  @Override
  public abstract boolean getMandatory();

  @Override
  public void repr(SkylarkPrinter printer) {
    printer.append("<attr.bool>");
  }

  @Override
  public TypeCoercer<?, Boolean> getTypeCoercer() {
    return coercer;
  }

  public static BoolAttribute of(Boolean preCoercionDefaultValue, String doc, boolean mandatory) {
    return ImmutableBoolAttribute.of(preCoercionDefaultValue, doc, mandatory);
  }
}
