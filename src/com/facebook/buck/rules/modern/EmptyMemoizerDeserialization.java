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

import com.facebook.buck.util.Memoizer;

/** Supports deserialization of a memoizer as just a default-constructed empty Memoizer. */
public class EmptyMemoizerDeserialization<T> implements CustomFieldSerialization<Memoizer<T>> {

  @Override
  public <E extends Exception> void serialize(Memoizer<T> value, ValueVisitor<E> serializer) {}

  @Override
  public <E extends Exception> Memoizer<T> deserialize(ValueCreator<E> deserializer) throws E {
    return new Memoizer<>();
  }
}
