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

package com.facebook.buck.skylark.function.attr;

import com.facebook.buck.core.rules.providers.Provider;
import com.facebook.buck.core.starlark.compatible.BuckSkylarkTypes;
import com.facebook.buck.core.starlark.rule.attr.AttributeHolder;
import com.facebook.buck.core.starlark.rule.attr.impl.BoolAttribute;
import com.facebook.buck.core.starlark.rule.attr.impl.DepAttribute;
import com.facebook.buck.core.starlark.rule.attr.impl.DepListAttribute;
import com.facebook.buck.core.starlark.rule.attr.impl.IntAttribute;
import com.facebook.buck.core.starlark.rule.attr.impl.IntListAttribute;
import com.facebook.buck.core.starlark.rule.attr.impl.OutputAttribute;
import com.facebook.buck.core.starlark.rule.attr.impl.OutputListAttribute;
import com.facebook.buck.core.starlark.rule.attr.impl.SourceAttribute;
import com.facebook.buck.core.starlark.rule.attr.impl.SourceListAttribute;
import com.facebook.buck.core.starlark.rule.attr.impl.StringAttribute;
import com.facebook.buck.core.starlark.rule.attr.impl.StringListAttribute;
import com.google.common.collect.ImmutableList;
import java.util.List;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Printer;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.StarlarkList;

/** Class that actually instantiates Attribute objects for user defined rules */
public class AttrModule implements AttrModuleApi {

  @Override
  public void repr(Printer printer) {
    printer.append("<attr>");
  }

  @Override
  public AttributeHolder intAttribute(
      StarlarkInt defaultValue, String doc, Boolean mandatory, StarlarkList<StarlarkInt> values)
      throws EvalException {
    List<StarlarkInt> validatedValues = Sequence.cast(values, StarlarkInt.class, null);
    return IntAttribute.of(
        defaultValue.toInt("defaultValue"),
        doc,
        mandatory,
        validatedValues.stream()
            .map(
                i -> {
                  try {
                    return i.toInt("defaultValue");
                  } catch (EvalException e) {
                    throw new RuntimeException(e);
                  }
                })
            .collect(ImmutableList.toImmutableList()));
  }

  @Override
  public AttributeHolder intListAttribute(
      StarlarkList<StarlarkInt> defaultValue, String doc, boolean mandatory, boolean allowEmpty)
      throws EvalException {
    ImmutableList<Integer> validatedDefaultValue =
        ImmutableList.copyOf(Sequence.cast(defaultValue, StarlarkInt.class, null)).stream()
            .map(
                i -> {
                  try {
                    return i.toInt("defaultValue");
                  } catch (EvalException e) {
                    throw new RuntimeException(e);
                  }
                })
            .collect(ImmutableList.toImmutableList());

    return IntListAttribute.of(validatedDefaultValue, doc, mandatory, allowEmpty);
  }

  @Override
  public AttributeHolder stringAttribute(
      String defaultValue, String doc, Boolean mandatory, StarlarkList<String> values)
      throws EvalException {
    List<String> validatedValues = Sequence.cast(values, String.class, null);

    return StringAttribute.of(defaultValue, doc, mandatory, validatedValues);
  }

  @Override
  public AttributeHolder stringListAttribute(
      StarlarkList<String> defaultValue, String doc, boolean mandatory, boolean allowEmpty)
      throws EvalException {
    ImmutableList<String> validatedDefaultValue =
        ImmutableList.copyOf(Sequence.cast(defaultValue, String.class, null));

    return StringListAttribute.of(validatedDefaultValue, doc, mandatory, allowEmpty);
  }

  @Override
  public AttributeHolder boolAttribute(boolean defaultValue, String doc, boolean mandatory) {
    return BoolAttribute.of(defaultValue, doc, mandatory);
  }

  @Override
  public AttributeHolder sourceListAttribute(
      StarlarkList<String> defaultValue, String doc, boolean mandatory, boolean allowEmpty)
      throws EvalException {
    ImmutableList<String> validatedDefaultValues =
        BuckSkylarkTypes.toJavaList(defaultValue, String.class, null);

    return SourceListAttribute.of(validatedDefaultValues, doc, mandatory, allowEmpty);
  }

  @Override
  public AttributeHolder sourceAttribute(Object defaultValue, String doc, boolean mandatory)
      throws EvalException {
    return SourceAttribute.of(defaultValue, doc, mandatory);
  }

  @Override
  public AttributeHolder depAttribute(
      Object defaultValue, String doc, boolean mandatory, StarlarkList<Provider<?>> providers)
      throws EvalException {
    ImmutableList<Provider<?>> validatedProviders =
        BuckSkylarkTypes.toJavaList(providers, Provider.class, "dep");

    return DepAttribute.of(defaultValue, doc, mandatory, validatedProviders);
  }

  @Override
  public AttributeHolder depListAttribute(
      StarlarkList<String> defaultValue,
      String doc,
      boolean mandatory,
      boolean allowEmpty,
      StarlarkList<Provider<?>> providers)
      throws EvalException {
    ImmutableList<String> validatedDefaultValues =
        BuckSkylarkTypes.toJavaList(defaultValue, String.class, "depList");
    ImmutableList<Provider<?>> validatedProviders =
        BuckSkylarkTypes.toJavaList(providers, Provider.class, "depList");

    return DepListAttribute.of(
        validatedDefaultValues, doc, mandatory, allowEmpty, validatedProviders);
  }

  @Override
  public AttributeHolder outputAttribute(Object defaultValue, String doc, boolean mandatory)
      throws EvalException {
    if (defaultValue == Starlark.NONE && !mandatory) {
      throw new EvalException("output attributes must have a default value, or be mandatory");
    }
    return OutputAttribute.of(defaultValue, doc, mandatory);
  }

  @Override
  public AttributeHolder outputListAttribute(
      StarlarkList<String> defaultValue, String doc, boolean mandatory, boolean allowEmpty)
      throws EvalException {
    List<String> validatedValues = Sequence.cast(defaultValue, String.class, null);
    return OutputListAttribute.of(validatedValues, doc, mandatory, allowEmpty);
  }
}
