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

package com.facebook.buck.rules.modern.impl;

import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.model.BaseName;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorSet;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.rules.modern.ValueCreator;
import com.facebook.buck.rules.modern.ValueTypeInfo;
import com.facebook.buck.rules.modern.ValueVisitor;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.reflect.TypeToken;
import java.util.Comparator;
import java.util.Optional;

/** TypeInfo for BuildTarget values. */
public class UnconfiguredBuildTargetTypeInfo implements ValueTypeInfo<UnconfiguredBuildTarget> {
  public static final UnconfiguredBuildTargetTypeInfo INSTANCE =
      new UnconfiguredBuildTargetTypeInfo();

  private static class Holder {
    // This must be in a separate class as BuildTargetTypeInfo can be loaded within a
    // Map.computeIfAbsent() call in ValueTypeInfoFactory.forTypeToken().
    // If this were in the outer class, that could trigger a recursive computeIfAbsent() call.
    private static final ValueTypeInfo<Optional<String>> cellNameTypeInfo =
        ValueTypeInfoFactory.forTypeToken(new TypeToken<Optional<String>>() {});
    private static final ValueTypeInfo<ImmutableSortedSet<String>> flavorsTypeInfo =
        ValueTypeInfoFactory.forTypeToken(new TypeToken<ImmutableSortedSet<String>>() {});
  }

  @Override
  public <E extends Exception> void visit(UnconfiguredBuildTarget value, ValueVisitor<E> visitor)
      throws E {
    UnflavoredBuildTarget unflavored = value.getUnflavoredBuildTarget();
    Holder.cellNameTypeInfo.visit(unflavored.getCell().getLegacyName(), visitor);
    visitor.visitString(unflavored.getBaseName().toString());
    visitor.visitString(unflavored.getLocalName());
    Holder.flavorsTypeInfo.visit(
        value.getFlavors().getSet().stream()
            .map(Flavor::getName)
            .collect(ImmutableSortedSet.toImmutableSortedSet(Comparator.naturalOrder())),
        visitor);
  }

  @Override
  public <E extends Exception> UnconfiguredBuildTarget create(ValueCreator<E> creator) throws E {
    CanonicalCellName cellName =
        CanonicalCellName.of(Holder.cellNameTypeInfo.createNotNull(creator));
    String baseName = creator.createString();
    String shortName = creator.createString();
    ImmutableSortedSet<Flavor> flavors =
        Holder.flavorsTypeInfo.createNotNull(creator).stream()
            .map(InternalFlavor::of)
            .collect(ImmutableSortedSet.toImmutableSortedSet(FlavorSet.FLAVOR_ORDERING));
    return UnconfiguredBuildTarget.of(
        UnflavoredBuildTarget.of(cellName, BaseName.of(baseName), shortName),
        FlavorSet.copyOf(flavors));
  }
}
