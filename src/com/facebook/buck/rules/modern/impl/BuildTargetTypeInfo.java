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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.model.UnflavoredBuildTarget;
import com.facebook.buck.rules.modern.ValueCreator;
import com.facebook.buck.rules.modern.ValueTypeInfo;
import com.facebook.buck.rules.modern.ValueVisitor;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.reflect.TypeToken;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;

/** TypeInfo for BuildTarget values. */
public class BuildTargetTypeInfo implements ValueTypeInfo<BuildTarget> {
  public static final ValueTypeInfo<BuildTarget> INSTANCE = new BuildTargetTypeInfo();

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
  public <E extends Exception> void visit(BuildTarget value, ValueVisitor<E> visitor) throws E {
    UnflavoredBuildTarget unflavored = value.getUnflavoredBuildTarget();
    visitor.visitPath(unflavored.getCellPath());
    Holder.cellNameTypeInfo.visit(unflavored.getCell(), visitor);
    visitor.visitString(unflavored.getBaseName());
    visitor.visitString(unflavored.getShortName());
    Holder.flavorsTypeInfo.visit(
        value
            .getFlavors()
            .stream()
            .map(Flavor::getName)
            .collect(ImmutableSortedSet.toImmutableSortedSet(Comparator.naturalOrder())),
        visitor);
  }

  @Override
  public <E extends Exception> BuildTarget create(ValueCreator<E> creator) throws E {
    Path cellPath = creator.createPath();
    Optional<String> cellName = Holder.cellNameTypeInfo.createNotNull(creator);
    String baseName = creator.createString();
    String shortName = creator.createString();
    ImmutableList<Flavor> flavors =
        Holder.flavorsTypeInfo
            .createNotNull(creator)
            .stream()
            .map(InternalFlavor::of)
            .collect(ImmutableList.toImmutableList());
    return BuildTarget.of(
        UnflavoredBuildTarget.of(cellPath, cellName, baseName, shortName), flavors);
  }
}
