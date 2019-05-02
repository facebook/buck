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
package com.facebook.buck.rules.modern;

import com.google.common.hash.HashCode;

/** This can be used for serialization/deserialization of HashCodes. */
public class HashCodeSerialization implements CustomFieldSerialization<HashCode> {

  @Override
  public <E extends Exception> void serialize(HashCode value, ValueVisitor<E> serializer) throws E {
    serializer.visitString(value.toString());
  }

  @Override
  public <E extends Exception> HashCode deserialize(ValueCreator<E> deserializer) throws E {
    return HashCode.fromString(deserializer.createString());
  }
}
