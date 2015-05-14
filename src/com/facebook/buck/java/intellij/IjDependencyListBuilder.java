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
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.immutables.value.Value;

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
   * Set of scopes supported by IntelliJ for the "orderEntry" element.
   */
  public enum Type {
    // Declaration order defines sorting order.
    MODULE("module"),
    LIBRARY("library");

    private final String value;
    Type(String value) {
      this.value = value;
    }

    @Override
    public String toString() {
      return value;
    }
  }

  @Value.Immutable
  @BuckStyleImmutable
  public abstract static class AbstractDependencyEntry
      implements Comparable<AbstractDependencyEntry> {
    public abstract Type getType();
    public abstract String getName();
    public abstract Scope getScope();
    public abstract boolean isExported();

    // This method is only needed so that StringTemplate can select the appropriate syntax.
    public boolean getIsModule() {
      return getType().equals(Type.MODULE);
    }

    @Override
    public int compareTo(AbstractDependencyEntry o) {
      int typeComparison = getType().compareTo(o.getType());
      if (typeComparison != 0) {
        return typeComparison;
      }
      return getName().compareTo(o.getName());
    }
  }

  private ImmutableSet.Builder<DependencyEntry> builder;

  public IjDependencyListBuilder() {
    builder = ImmutableSortedSet.naturalOrder();
  }

  public void addModule(String name, Scope scope, boolean exported) {
    builder.add(
        DependencyEntry.builder()
            .setType(Type.MODULE)
            .setName(name)
            .setScope(scope)
            .setExported(exported)
            .build());
  }

  public void addLibrary(String name, Scope scope, boolean exported) {
    builder.add(
        DependencyEntry.builder()
            .setType(Type.LIBRARY)
            .setName(name)
            .setScope(scope)
            .setExported(exported)
            .build());
  }

  public ImmutableSet<DependencyEntry> build() {
    return builder.build();
  }
}
