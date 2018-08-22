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

package com.facebook.buck.rules.modern;

import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * A ValueCreator can be used to create the values referenced from a Buildable. This is similar to
 * ValueVisitor but returns a value instead of taking one. Used for deserialization.
 */
public interface ValueCreator<E extends Exception> {
  AddsToRuleKey createDynamic() throws E;

  <T> ImmutableList<T> createList(ValueTypeInfo<T> innerType) throws E;

  <T> ImmutableSet<T> createSet(ValueTypeInfo<T> innerType) throws E;

  <T> ImmutableSortedSet<T> createSortedSet(ValueTypeInfo<T> innerType) throws E;

  <K, V> ImmutableMap<K, V> createMap(ValueTypeInfo<K> keyType, ValueTypeInfo<V> valueType)
      throws E;

  <K, V> ImmutableSortedMap<K, V> createSortedMap(
      ValueTypeInfo<K> keyType, ValueTypeInfo<V> valueType) throws E;

  @Nullable
  <T> T createNullable(ValueTypeInfo<T> inner) throws E;

  <T> Optional<T> createOptional(ValueTypeInfo<T> innerType) throws E;

  OutputPath createOutputPath() throws E;

  SourcePath createSourcePath() throws E;

  Path createPath() throws E;

  String createString() throws E;

  Character createCharacter() throws E;

  Boolean createBoolean() throws E;

  Byte createByte() throws E;

  Short createShort() throws E;

  Integer createInteger() throws E;

  Long createLong() throws E;

  Float createFloat() throws E;

  Double createDouble() throws E;

  <T> T createSpecial(Class<T> valueClass, Object... args) throws E;
}
