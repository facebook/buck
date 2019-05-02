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

import com.facebook.buck.util.environment.Platform;

/** This can be used for serialization/deserialization of Platform. */
public class PlatformSerialization implements CustomFieldSerialization<Platform> {

  @Override
  public <E extends Exception> void serialize(Platform value, ValueVisitor<E> serializer) throws E {
    // do nothing
  }

  @Override
  public <E extends Exception> Platform deserialize(ValueCreator<E> deserializer) throws E {
    return Platform.detect();
  }
}
