/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.java.intellij;

import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.immutables.value.Value;

import javax.annotation.Nullable;

/**
 * Represents the dependencies of an {@link IjModule} in a form intended to be consumable by the
 * {@link IjProjectWriter}.
 */
public class IjDependencyListBuilder {

  /**
   * Set of scopes supported by IntelliJ for the "orderEntry" element.
   */
  public enum Scope {
    COMPILE("COMPILE"),
    PROVIDED("PROVIDED"),
    RUNTIME("RUNTIME"),
    TEST("TEST");

    private final String value;
    Scope(String value) {
      this.value = value;
    }

    @Override
    public String toString() {
      return value;
    }
  }

  /**
   * Set of types supported by IntelliJ for the "orderEntry" element.
   */
  public enum Type {
    MODULE("module"),
    LIBRARY("library"),
    SOURCE_FOLDER("sourceFolder");

    private final String value;
    Type(String value) {
      this.value = value;
    }

    @Override
    public String toString() {
      return value;
    }
  }

  public enum SortOrder {
    // Declaration order defines sorting order.
    MODULE,
    LIBRARY,
    SOURCE_FOLDER,
    COMPILED_SHADOW
  }

  /**
   * The design of this classes API is tied to how the corresponding StringTemplate template
   * interacts with it.
   */
  @Value.Immutable
  @BuckStyleImmutable
  public abstract static class AbstractDependencyEntry
      implements Comparable<AbstractDependencyEntry> {
    protected abstract Optional<DependencyEntryData> getData();
    protected abstract Type getType();
    protected abstract SortOrder getSortOrder();

    @Nullable
    public DependencyEntryData getModule() {
      if (getType().equals(Type.MODULE)) {
        return getData().get();
      } else {
        return null;
      }
    }

    @Nullable
    public DependencyEntryData getLibrary() {
      if (getType().equals(Type.LIBRARY)) {
        return getData().get();
      } else {
        return null;
      }
    }

    public boolean getSourceFolder() {
      if (getType().equals(Type.SOURCE_FOLDER)) {
        return true;
      } else {
        return false;
      }
    }

    @Value.Check
    protected void dataOnlyAbsentForSourceFolder() {
      Preconditions.checkArgument(getData().isPresent() || getType().equals(Type.SOURCE_FOLDER));
    }

    @Override
    public int compareTo(AbstractDependencyEntry o) {
      int sortComparison = getSortOrder().compareTo(o.getSortOrder());
      if (sortComparison != 0) {
        return sortComparison;
      }
      if (getData().isPresent() && o.getData().isPresent()) {
        return getData().get().getName().compareTo(o.getData().get().getName());
      }
      // We can only get here if there is more than one SOURCE_FOLDER entry in the list or if
      // a new data-less type was added without updating this code.
      Preconditions.checkState(false);
      return 0;
    }
  }

  @Value.Immutable
  @BuckStyleImmutable
  public abstract static class AbstractDependencyEntryData {
    public abstract String getName();
    public abstract Scope getScope();
    public abstract boolean getExported();
  }

  private ImmutableSet.Builder<DependencyEntry> builder;

  public IjDependencyListBuilder() {
    builder = ImmutableSortedSet.naturalOrder();

    builder.add(
        DependencyEntry.builder()
            .setType(Type.SOURCE_FOLDER)
            .setSortOrder(SortOrder.SOURCE_FOLDER)
            .build());
  }

  public void addModule(String name, Scope scope, boolean exported) {
    builder.add(
        DependencyEntry.builder()
            .setType(Type.MODULE)
            .setSortOrder(SortOrder.MODULE)
            .setData(DependencyEntryData.builder()
                    .setName(name)
                    .setScope(scope)
                    .setExported(exported)
                    .build())
            .build());
  }

  public void addCompiledShadow(String name) {
    builder.add(
        DependencyEntry.builder()
            .setType(Type.LIBRARY)
            .setSortOrder(SortOrder.COMPILED_SHADOW)
            .setData(DependencyEntryData.builder()
                    .setName(name)
                    .setScope(Scope.PROVIDED)
                    .setExported(true)
                    .build())
            .build());
  }

  public void addLibrary(String name, Scope scope, boolean exported) {
    builder.add(
        DependencyEntry.builder()
            .setType(Type.LIBRARY)
            .setSortOrder(SortOrder.LIBRARY)
            .setData(DependencyEntryData.builder()
                    .setName(name)
                    .setScope(scope)
                    .setExported(exported)
                    .build())
            .build());
  }

  public ImmutableSet<DependencyEntry> build() {
    return builder.build();
  }
}
