/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.skylark.function;

import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Ordering;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;
import com.google.devtools.build.lib.skylarkinterface.SkylarkValue;
import com.google.devtools.build.lib.syntax.BuiltinFunction;
import com.google.devtools.build.lib.syntax.ClassObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Represents a {@code native} variable available in Skylark extension files. It's responsible for
 * handling calls like {@code native.java_library(...)} in {@code .bzl} files.
 */
public class NativeModule implements ClassObject, SkylarkValue {
  private final ImmutableMap<String, BuiltinFunction> buckRuleFunctionRegistry;

  public NativeModule(
      ImmutableList<BuiltinFunction> buckRuleFunctions, BuiltinFunction... otherFunctions) {
    ImmutableMap.Builder<String, BuiltinFunction> registryBuilder = ImmutableMap.builder();
    for (BuiltinFunction buckRuleFunction : buckRuleFunctions) {
      registryBuilder.put(buckRuleFunction.getName(), buckRuleFunction);
    }
    for (BuiltinFunction builtinFunction : otherFunctions) {
      registryBuilder.put(builtinFunction.getName(), builtinFunction);
    }
    buckRuleFunctionRegistry = registryBuilder.build();
  }

  @Nullable
  @Override
  public BuiltinFunction getValue(String name) {
    return buckRuleFunctionRegistry.get(name);
  }

  @Override
  public ImmutableCollection<String> getKeys() {
    return buckRuleFunctionRegistry.keySet();
  }

  @Nullable
  @Override
  public String errorMessage(String name) {
    String suffix =
        "Available attributes: " + Joiner.on(", ").join(Ordering.natural().sortedCopy(getKeys()));
    return "native object does not have an attribute " + name + "\n" + suffix;
  }

  @Override
  public void repr(SkylarkPrinter printer) {
    boolean first = true;
    printer.append("struct(");
    // Sort by key to ensure deterministic output.
    for (String key : Ordering.natural().sortedCopy(getKeys())) {
      if (!first) {
        printer.append(", ");
      }
      first = false;
      printer.append(key);
      printer.append(" = ");
      printer.repr(getValue(key));
    }
    printer.append(")");
  }

  @Override
  public boolean isImmutable() {
    return true;
  }

  @Override
  public int hashCode() {
    List<String> keys = new ArrayList<>(getKeys());
    Collections.sort(keys);
    List<Object> objectsToHash = new ArrayList<>();
    for (String key : keys) {
      objectsToHash.add(key);
      objectsToHash.add(getValue(key));
    }
    return Objects.hashCode(objectsToHash.toArray());
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof NativeModule)) {
      return false;
    }
    NativeModule other = (NativeModule) obj;
    return this == other || this.buckRuleFunctionRegistry.equals(other.buckRuleFunctionRegistry);
  }
}
