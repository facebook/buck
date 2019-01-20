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

import java.nio.file.Path;

/**
 * This can be used for serialization/deserialization of Paths (in such a way that absolute paths
 * will be updated for the new context).
 *
 * <p>This should be used rarely as rules shouldn't be referencing paths.
 */
public class PathSerialization implements CustomFieldSerialization<Path> {

  @Override
  public <E extends Exception> void serialize(Path value, ValueVisitor<E> serializer) throws E {
    serializer.visitPath(value);
  }

  @Override
  public <E extends Exception> Path deserialize(ValueCreator<E> deserializer) throws E {
    return deserializer.createPath();
  }
}
