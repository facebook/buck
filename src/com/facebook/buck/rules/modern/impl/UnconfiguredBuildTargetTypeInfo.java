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

import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.UnconfiguredBuildTargetView;
import com.facebook.buck.core.model.UnflavoredBuildTargetView;
import com.facebook.buck.core.model.impl.ImmutableUnconfiguredBuildTargetView;
import com.facebook.buck.core.model.impl.ImmutableUnflavoredBuildTargetView;
import com.facebook.buck.rules.modern.ValueCreator;
import com.facebook.buck.rules.modern.ValueTypeInfo;
import com.facebook.buck.rules.modern.ValueVisitor;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.reflect.TypeToken;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

/** TypeInfo for BuildTarget values. */
public class UnconfiguredBuildTargetTypeInfo implements ValueTypeInfo<UnconfiguredBuildTargetView> {
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
  public <E extends Exception> void visit(
      UnconfiguredBuildTargetView value, ValueVisitor<E> visitor) throws E {
    UnflavoredBuildTargetView unflavored = value.getUnflavoredBuildTargetView();
    visitor.visitPath(unflavored.getCellPath());
    Holder.cellNameTypeInfo.visit(unflavored.getCell(), visitor);
    visitor.visitString(unflavored.getBaseName());
    visitor.visitString(unflavored.getShortName());
    Holder.flavorsTypeInfo.visit(
        value.getFlavors().stream()
            .map(Flavor::getName)
            .collect(ImmutableSortedSet.toImmutableSortedSet(Comparator.naturalOrder())),
        visitor);
  }

  @Override
  public <E extends Exception> UnconfiguredBuildTargetView create(ValueCreator<E> creator)
      throws E {
    Path cellPath = creator.createPath();
    Optional<String> cellName = Holder.cellNameTypeInfo.createNotNull(creator);
    String baseName = creator.createString();
    String shortName = creator.createString();
    Stream<Flavor> flavors =
        Holder.flavorsTypeInfo.createNotNull(creator).stream().map(InternalFlavor::of);
    return ImmutableUnconfiguredBuildTargetView.of(
        ImmutableUnflavoredBuildTargetView.of(cellPath, cellName, baseName, shortName), flavors);
  }
}
