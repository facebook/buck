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
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.Runtime;
import com.google.devtools.build.lib.syntax.SkylarkList;
import java.util.List;

/** Class that actually instantiates Attribute objects for user defined rules */
public class AttrModule implements AttrModuleApi {

  @Override
  public void repr(SkylarkPrinter printer) {
    printer.append("<attr>");
  }

  @Override
  public AttributeHolder intAttribute(
      Integer defaultValue, String doc, Boolean mandatory, SkylarkList<Integer> values)
      throws EvalException {
    List<Integer> validatedValues = SkylarkList.castList(values, Integer.class, null);
    return IntAttribute.of(defaultValue, doc, mandatory, validatedValues);
  }

  @Override
  public AttributeHolder intListAttribute(
      SkylarkList<Integer> defaultValue, String doc, boolean mandatory, boolean allowEmpty)
      throws EvalException {
    ImmutableList<Integer> validatedDefaultValue =
        ImmutableList.copyOf(defaultValue.getContents(Integer.class, null));

    return IntListAttribute.of(validatedDefaultValue, doc, mandatory, allowEmpty);
  }

  @Override
  public AttributeHolder stringAttribute(
      String defaultValue, String doc, Boolean mandatory, SkylarkList<String> values)
      throws EvalException {
    List<String> validatedValues = SkylarkList.castList(values, String.class, null);

    return StringAttribute.of(defaultValue, doc, mandatory, validatedValues);
  }

  @Override
  public AttributeHolder stringListAttribute(
      SkylarkList<String> defaultValue, String doc, boolean mandatory, boolean allowEmpty)
      throws EvalException {
    ImmutableList<String> validatedDefaultValue =
        ImmutableList.copyOf(defaultValue.getContents(String.class, null));

    return StringListAttribute.of(validatedDefaultValue, doc, mandatory, allowEmpty);
  }

  @Override
  public AttributeHolder boolAttribute(boolean defaultValue, String doc, boolean mandatory) {
    return BoolAttribute.of(defaultValue, doc, mandatory);
  }

  @Override
  public AttributeHolder sourceListAttribute(
      SkylarkList<String> defaultValue, String doc, boolean mandatory, boolean allowEmpty)
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
      Object defaultValue, String doc, boolean mandatory, SkylarkList<Provider<?>> providers)
      throws EvalException {
    ImmutableList<Provider<?>> validatedProviders =
        BuckSkylarkTypes.toJavaList(providers, Provider.class, null);

    return DepAttribute.of(defaultValue, doc, mandatory, validatedProviders);
  }

  @Override
  public AttributeHolder depListAttribute(
      SkylarkList<String> defaultValue,
      String doc,
      boolean mandatory,
      boolean allowEmpty,
      SkylarkList<Provider<?>> providers)
      throws EvalException {
    ImmutableList<String> validatedDefaultValues =
        BuckSkylarkTypes.toJavaList(defaultValue, String.class, null);
    ImmutableList<Provider<?>> validatedProviders =
        BuckSkylarkTypes.toJavaList(providers, Provider.class, null);

    return DepListAttribute.of(
        validatedDefaultValues, doc, mandatory, allowEmpty, validatedProviders);
  }

  @Override
  public AttributeHolder outputAttribute(
      Object defaultValue, String doc, boolean mandatory, Location location) throws EvalException {
    if (defaultValue == Runtime.NONE && !mandatory) {
      throw new EvalException(
          location, "output attributes must have a default value, or be mandatory");
    }
    return OutputAttribute.of(defaultValue, doc, mandatory);
  }

  @Override
  public AttributeHolder outputListAttribute(
      SkylarkList<String> defaultValue, String doc, boolean mandatory, boolean allowEmpty)
      throws EvalException {
    List<String> validatedValues = defaultValue.getContents(String.class, null);
    return OutputListAttribute.of(validatedValues, doc, mandatory, allowEmpty);
  }
}
