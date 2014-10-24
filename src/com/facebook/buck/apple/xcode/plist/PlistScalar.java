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

package com.facebook.buck.apple.xcode.plist;

import com.google.common.base.Preconditions;

public class PlistScalar extends PlistValue {
  enum Type {
    STRING,
    INTEGER,
    BOOLEAN,
  }

  private final Type type;
  private final Object value;

  private PlistScalar(Type type, Object value) {
    this.type = type;
    this.value = value;
  }

  public Type getType() {
    return type;
  }

  public boolean getBooleanValue() {
    Preconditions.checkState(
        Type.BOOLEAN.equals(type), "Must be boolean but %s was %s.", value, type);
    return (boolean) value;
  }

  public int getIntValue() {
    Preconditions.checkState(Type.INTEGER.equals(type), "Must be int but %s was %s.", value, type);
    return (int) value;
  }

  public String getStringValue() {
    Preconditions.checkState(
        Type.STRING.equals(type), "Must be string but %s was %s.", value, type);
    return (String) value;
  }

  @Override
  <E> E acceptVisitor(PlistVisitor<E> serializer) {
    return serializer.visit(this);
  }

  public static PlistScalar of(boolean value) {
    return new PlistScalar(Type.BOOLEAN, value);
  }

  public static PlistScalar of(int value) {
    return new PlistScalar(Type.INTEGER, value);
  }

  public static PlistScalar of(String value) {
    return new PlistScalar(Type.STRING, value);
  }
}
