/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.rules.modern.impl;

import com.facebook.buck.rules.AddsToRuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.modern.ClassInfo;
import com.facebook.buck.rules.modern.OutputPath;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Optional;

/**
 * A ValueVisitor can be used to visit all the values referenced from a Buildable. This can be used
 * for things like deriving all the inputs or outputs.
 */
public interface ValueVisitor<E extends Exception> {
  <T> void visitList(ImmutableList<T> value, ValueTypeInfo<T> innerType) throws E;

  <T> void visitSet(ImmutableSortedSet<T> value, ValueTypeInfo<T> innerType) throws E;

  <T> void visitOptional(Optional<T> value, ValueTypeInfo<T> innerType) throws E;

  void visitOutputPath(OutputPath value) throws E;

  void visitSourcePath(SourcePath value) throws E;

  <T> void visitField(Field field, T value, ValueTypeInfo<T> valueTypeInfo) throws E;

  <T extends AddsToRuleKey> void visitDynamic(T value, ClassInfo<T> classInfo) throws E;

  void visitPath(Path path) throws E;

  void visitString(String value) throws E;

  void visitCharacter(Character value) throws E;

  void visitBoolean(Boolean value) throws E;

  void visitByte(Byte value) throws E;

  void visitShort(Short value) throws E;

  void visitInteger(Integer value) throws E;

  void visitLong(Long value) throws E;

  void visitFloat(Float value) throws E;

  void visitDouble(Double value) throws E;
}
