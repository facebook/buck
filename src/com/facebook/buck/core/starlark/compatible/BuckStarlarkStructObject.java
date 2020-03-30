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

package com.facebook.buck.core.starlark.compatible;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Ordering;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;
import com.google.devtools.build.lib.skylarkinterface.SkylarkValue;
import com.google.devtools.build.lib.syntax.ClassObject;
import com.google.devtools.build.lib.syntax.EvalException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import javax.annotation.Nullable;

/**
 * Marks a Java object as accessible by Skylark as a struct-like object called {@link ClassObject}.
 * This also marks it as a {@link SkylarkValue}.
 *
 * <p>A struct like object is an object containing fields accessible via the dot syntax, like {@code
 * obj.field}.
 *
 * <p>We currently do not support method calls.
 */
public abstract class BuckStarlarkStructObject implements ClassObject, SkylarkValue {

  private @Nullable ImmutableMap<String, Method> values;

  @Nullable
  @Override
  public Object getValue(String name) throws EvalException {
    try {
      @Nullable Method method = getMethods().get(name);
      if (method == null) {
        throw new EvalException(Location.BUILTIN, getErrorMessageForUnknownField(name));
      }

      // TODO: make mappings for skylark types here: e.g Optionals -> Runtime.NONE. SkylarkList/Dict
      // conversions.
      return method.invoke(this);
    } catch (IllegalAccessException | InvocationTargetException e) {
      throw new EvalException(Location.BUILTIN, e);
    }
  }

  protected ImmutableMap<String, Method> getMethods() {
    if (values == null) {
      values = MethodLookup.getMethods(getDeclaredClass());
    }
    return values;
  }

  /** @return the class for which all declared methods on it are considered skylark accessible. */
  protected abstract Class<?> getDeclaredClass();

  @Override
  public ImmutableCollection<String> getFieldNames() {
    return getMethods().keySet();
  }

  @Nullable
  @Override
  public String getErrorMessageForUnknownField(String field) {
    return String.format("%s has no field: %s", getDeclaredClass(), field);
  }

  @Override
  public void repr(SkylarkPrinter printer) {
    boolean first = true;
    printer.format("%s(", getDeclaredClass());
    // Sort by key to ensure deterministic output.
    for (String fieldName : Ordering.natural().sortedCopy(getFieldNames())) {
      if (!first) {
        printer.append(", ");
      }
      first = false;
      printer.append(fieldName);
      printer.append(" = ");
      try {
        printer.repr(getValue(fieldName));
      } catch (EvalException e) {
        printer.repr(null);
      }
    }
    printer.append(")");
  }
}
