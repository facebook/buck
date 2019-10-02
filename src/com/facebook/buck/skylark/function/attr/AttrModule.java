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

import com.facebook.buck.core.rules.providers.Provider;
import com.facebook.buck.core.starlark.compatible.BuckSkylarkTypes;
import com.facebook.buck.core.starlark.rule.attr.AttributeHolder;
import com.facebook.buck.core.starlark.rule.attr.impl.ImmutableBoolAttribute;
import com.facebook.buck.core.starlark.rule.attr.impl.ImmutableDepAttribute;
import com.facebook.buck.core.starlark.rule.attr.impl.ImmutableDepListAttribute;
import com.facebook.buck.core.starlark.rule.attr.impl.ImmutableIntAttribute;
import com.facebook.buck.core.starlark.rule.attr.impl.ImmutableIntListAttribute;
import com.facebook.buck.core.starlark.rule.attr.impl.ImmutableSourceAttribute;
import com.facebook.buck.core.starlark.rule.attr.impl.ImmutableSourceListAttribute;
import com.facebook.buck.core.starlark.rule.attr.impl.ImmutableStringAttribute;
import com.facebook.buck.core.starlark.rule.attr.impl.ImmutableStringListAttribute;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;
import com.google.devtools.build.lib.syntax.EvalException;
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
    return new ImmutableIntAttribute(defaultValue, doc, mandatory, validatedValues);
  }

  @Override
  public AttributeHolder intListAttribute(
      SkylarkList<Integer> defaultValue, String doc, boolean mandatory, boolean allowEmpty)
      throws EvalException {
    ImmutableList<Integer> validatedDefaultValue =
        ImmutableList.copyOf(defaultValue.getContents(Integer.class, null));

    return new ImmutableIntListAttribute(validatedDefaultValue, doc, mandatory, allowEmpty);
  }

  @Override
  public AttributeHolder stringAttribute(
      String defaultValue, String doc, Boolean mandatory, SkylarkList<String> values)
      throws EvalException {
    List<String> validatedValues = SkylarkList.castList(values, String.class, null);

    return new ImmutableStringAttribute(defaultValue, doc, mandatory, validatedValues);
  }

  @Override
  public AttributeHolder stringListAttribute(
      SkylarkList<String> defaultValue, String doc, boolean mandatory, boolean allowEmpty)
      throws EvalException {
    ImmutableList<String> validatedDefaultValue =
        ImmutableList.copyOf(defaultValue.getContents(String.class, null));

    return new ImmutableStringListAttribute(validatedDefaultValue, doc, mandatory, allowEmpty);
  }

  @Override
  public AttributeHolder boolAttribute(boolean defaultValue, String doc, boolean mandatory) {
    return new ImmutableBoolAttribute(defaultValue, doc, mandatory);
  }

  @Override
  public AttributeHolder sourceListAttribute(
      SkylarkList<String> defaultValue, String doc, boolean mandatory, boolean allowEmpty)
      throws EvalException {
    List<String> validatedDefaultValues = defaultValue.getContents(String.class, null);

    return new ImmutableSourceListAttribute(validatedDefaultValues, doc, mandatory, allowEmpty);
  }

  @Override
  public AttributeHolder sourceAttribute(Object defaultValue, String doc, boolean mandatory)
      throws EvalException {
    return new ImmutableSourceAttribute(defaultValue, doc, mandatory);
  }

  @Override
  public AttributeHolder depAttribute(
      Object defaultValue, String doc, boolean mandatory, SkylarkList<Provider<?>> providers)
      throws EvalException {
    ImmutableList<Provider<?>> validatedProviders =
        BuckSkylarkTypes.toJavaList(providers, Provider.class, null);

    return new ImmutableDepAttribute(defaultValue, doc, mandatory, validatedProviders);
  }

  @Override
  public AttributeHolder depListAttribute(
      SkylarkList<String> defaultValue,
      String doc,
      boolean mandatory,
      boolean allowEmpty,
      SkylarkList<Provider<?>> providers)
      throws EvalException {
    List<String> validatedDefaultValues = defaultValue.getContents(String.class, null);
    ImmutableList<Provider<?>> validatedProviders =
        BuckSkylarkTypes.toJavaList(providers, Provider.class, null);

    return new ImmutableDepListAttribute(
        validatedDefaultValues, doc, mandatory, allowEmpty, validatedProviders);
  }
}
