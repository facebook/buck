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

import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.cell.nameresolver.DefaultCellNameResolver;
import com.facebook.buck.rules.modern.impl.ValueTypeInfoFactory;
import com.google.common.collect.ImmutableSet;
import com.google.common.reflect.TypeToken;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** This can be used for serialization/deserialization of {@link CellNameResolver}. */
public class CellNameResolverSerialization implements CustomFieldSerialization<CellNameResolver> {

  static final ValueTypeInfo<Optional<String>> TYPE_INFO =
      ValueTypeInfoFactory.forTypeToken(new TypeToken<Optional<String>>() {});

  @Override
  public <E extends Exception> void serialize(CellNameResolver value, ValueVisitor<E> serializer)
      throws E {
    serializer.visitSet(value.getKnownCells().keySet(), TYPE_INFO);
  }

  @Override
  public <E extends Exception> CellNameResolver deserialize(ValueCreator<E> deserializer) throws E {
    ImmutableSet<Optional<String>> cellNames = deserializer.createSet(TYPE_INFO);
    Map<Optional<String>, CanonicalCellName> knownCells =
        cellNames.stream()
            .collect(
                Collectors.toMap(Function.identity(), cellName -> CanonicalCellName.of(cellName)));
    return DefaultCellNameResolver.of(knownCells);
  }
}
