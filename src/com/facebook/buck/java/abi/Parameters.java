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

package com.facebook.buck.java.abi;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.TypeMirror;

class Parameters implements Renderable {

  private final Iterable<? extends VariableElement> allParams;

  public Parameters(Iterable<? extends VariableElement> allParams) {
    this.allParams = allParams;
  }

  @Override
  public void appendTo(StringBuilder builder) {
    List<String> converted = new ArrayList<>();

    for (VariableElement param : allParams) {
      converted.add(getTypeAndName(param));
    }

    Joiner.on(", ").appendTo(builder, converted);
  }

  private String getTypeAndName(VariableElement input) {
    if (isVarArg(input.asType())) {
      return ((ArrayType) input.asType()).getComponentType() + "...";
    }

    return input.asType().toString();
  }

  private boolean isVarArg(TypeMirror type) {
    if (!(type instanceof ArrayType)) {
      return false;
    }

    try {
      // This has only been tested on the Oracle JDK.
      Method isVarargs = type.getClass().getMethod("isVarargs");
      isVarargs.setAccessible(true);
      Object value = isVarargs.invoke(type);
      if (value == null) {
        return false;
      }
      return (Boolean) value;
    } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
      throw new RuntimeException(e);
    }
  }
}
