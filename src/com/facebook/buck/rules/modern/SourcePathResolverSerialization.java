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

package com.facebook.buck.rules.modern;

import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;

/**
 * Uses ValueCreator.createSpecial() to create a SourcePathResolverAdapter. Ideally, build rules
 * don't hold references to SourcePathResolvers, but this custom behavior can assist in migrating to
 * ModernBuildRule.
 */
public class SourcePathResolverSerialization
    implements CustomFieldSerialization<SourcePathResolverAdapter> {
  @Override
  public <E extends Exception> void serialize(
      SourcePathResolverAdapter value, ValueVisitor<E> serializer) throws E {}

  @Override
  public <E extends Exception> SourcePathResolverAdapter deserialize(ValueCreator<E> deserializer)
      throws E {
    return deserializer.createSpecial(SourcePathResolverAdapter.class);
  }
}
