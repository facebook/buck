// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.shaking;

import com.android.tools.r8.graph.DexType;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import it.unimi.dsi.fastutil.objects.Object2BooleanArrayMap;
import it.unimi.dsi.fastutil.objects.Object2BooleanMap;
import it.unimi.dsi.fastutil.objects.Object2BooleanMap.Entry;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public abstract class ProguardClassNameList {

  public static Builder builder() {
    return new Builder();
  }

  public static ProguardClassNameList emptyList() {
    return new EmptyClassNameList();
  }

  public static ProguardClassNameList singletonList(ProguardTypeMatcher matcher) {
    return new SingleClassNameList(matcher);
  }

  public abstract int size();

  public static class Builder {

    /**
     * Map used to store pairs of patterns and whether they are negated.
     */
    private Object2BooleanMap<ProguardTypeMatcher> matchers = new Object2BooleanArrayMap<>();

    private Builder() {
    }

    public Builder addClassName(boolean isNegated, ProguardTypeMatcher className) {
      matchers.put(className, isNegated);
      return this;
    }

    ProguardClassNameList build() {
      if (matchers.containsValue(true)) {
        // At least one pattern is negated.
        return new MixedClassNameList(matchers);
      } else {
        if (matchers.size() == 1) {
          return new SingleClassNameList(Iterables.getOnlyElement(matchers.keySet()));
        } else {
          return new PositiveClassNameList(matchers.keySet());
        }
      }
    }

  }

  public abstract void writeTo(StringBuilder builder);

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder();
    writeTo(builder);
    return builder.toString();
  }

  public abstract List<DexType> asSpecificDexTypes();

  public abstract boolean matches(DexType type);

  public abstract void forEachTypeMatcher(Consumer<ProguardTypeMatcher> consumer);

  private static class EmptyClassNameList extends ProguardClassNameList {

    private EmptyClassNameList() {
    }

    @Override
    public int size() {
      return 0;
    }

    @Override
    public void writeTo(StringBuilder builder) {
    }

    @Override
    public List<DexType> asSpecificDexTypes() {
      return null;
    }

    @Override
    public boolean matches(DexType type) {
      return false;
    }

    @Override
    public void forEachTypeMatcher(Consumer<ProguardTypeMatcher> consumer) {
    }
  }

  private static class SingleClassNameList extends ProguardClassNameList {

    private final ProguardTypeMatcher className;

    private SingleClassNameList(ProguardTypeMatcher className) {
      this.className = className;
    }

    @Override
    public int size() {
      return 1;
    }

    @Override
    public void writeTo(StringBuilder builder) {
      builder.append(className.toString());
    }

    @Override
    public List<DexType> asSpecificDexTypes() {
      DexType specific = className.getSpecificType();
      return specific == null ? null : Collections.singletonList(specific);
    }

    @Override
    public boolean matches(DexType type) {
      return className.matches(type);
    }

    @Override
    public void forEachTypeMatcher(Consumer<ProguardTypeMatcher> consumer) {
      consumer.accept(className);
    }
  }

  private static class PositiveClassNameList extends ProguardClassNameList {

    private final ImmutableList<ProguardTypeMatcher> classNames;

    private PositiveClassNameList(Collection<ProguardTypeMatcher> classNames) {
      this.classNames = ImmutableList.copyOf(classNames);
    }

    @Override
    public int size() {
      return classNames.size();
    }

    @Override
    public void writeTo(StringBuilder builder) {
      boolean first = true;
      for (ProguardTypeMatcher className : classNames) {
        if (!first) {
          builder.append(',');
        }
        builder.append(className);
        first = false;
      }
    }

    @Override
    public List<DexType> asSpecificDexTypes() {
      if (classNames.stream().allMatch(k -> k.getSpecificType() != null)) {
        return classNames.stream().map(ProguardTypeMatcher::getSpecificType)
            .collect(Collectors.toList());
      }
      return null;
    }

    @Override
    public boolean matches(DexType type) {
      return classNames.stream().anyMatch(name -> name.matches(type));
    }

    @Override
    public void forEachTypeMatcher(Consumer<ProguardTypeMatcher> consumer) {
      classNames.forEach(consumer);
    }
  }

  private static class MixedClassNameList extends ProguardClassNameList {

    private final Object2BooleanMap<ProguardTypeMatcher> classNames;

    private MixedClassNameList(Object2BooleanMap<ProguardTypeMatcher> classNames) {
      this.classNames = classNames;
    }

    @Override
    public int size() {
      return classNames.size();
    }

    @Override
    public void writeTo(StringBuilder builder) {
      boolean first = true;
      for (Entry<ProguardTypeMatcher> className : classNames.object2BooleanEntrySet()) {
        if (!first) {
          builder.append(',');
        }
        if (className.getBooleanValue()) {
          builder.append('!');
        }
        builder.append(className.getKey().toString());
        first = false;
      }
    }

    @Override
    public List<DexType> asSpecificDexTypes() {
      return null;
    }

    @Override
    public boolean matches(DexType type) {
      for (Entry<ProguardTypeMatcher> className : classNames.object2BooleanEntrySet()) {
        if (className.getKey().matches(type)) {
          // If we match a negation, abort as non-match. If we match a positive, return true.
          return !className.getBooleanValue();
        }
      }
      return false;
    }

    @Override
    public void forEachTypeMatcher(Consumer<ProguardTypeMatcher> consumer) {
      classNames.object2BooleanEntrySet().forEach(entry -> consumer.accept(entry.getKey()));
    }
  }
}
