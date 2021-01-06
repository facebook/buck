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

package com.facebook.buck.core.exceptions;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Stack-trace for providing better diagnostics when working with dependency graph.
 *
 * <p>This type must not be used for anything except for providing error diagnostics.
 */
public final class DependencyStack {
  /**
   * Marker interface for stack trace elements. It is a marker to avoid accidental storing of
   * objects of inappropriate types in the trace.
   */
  public interface Element extends ProvidesElement {
    @Override
    default Element getElement() {
      return this;
    }
  }

  /** Types which have something to be stored in stack trace */
  public interface ProvidesElement {
    Element getElement();
  }

  /** Tail stack or {@code null} for an empty stack */
  @Nullable private final DependencyStack parent;
  /** Last element. Undefined for empty stack */
  private final Object where;

  private DependencyStack(@Nullable DependencyStack parent, Object where) {
    this.parent = parent;
    this.where = where;
  }

  private static final DependencyStack ROOT = new DependencyStack(null, new Element() {});

  /** Create an empty stack */
  public static DependencyStack root() {
    return ROOT;
  }

  /** Create a single element stack */
  public static DependencyStack top(Element top) {
    return root().child(top);
  }

  /** Create a single element stack */
  public static DependencyStack top(String top) {
    return root().child(top);
  }

  /** Cons */
  public DependencyStack child(Element where) {
    return new DependencyStack(this, where);
  }

  /** Cons */
  public DependencyStack child(String where) {
    return new DependencyStack(this, where);
  }

  /** Check if stack is empty */
  public boolean isEmpty() {
    return this == ROOT;
  }

  /** Collect the stack elements, top first */
  public ImmutableList<Object> collect() {
    ImmutableList.Builder<Object> builder = ImmutableList.builder();

    DependencyStack next = this;
    while (next.parent != null) {
      builder.add(next.where);
      next = next.parent;
    }

    return builder.build();
  }

  /**
   * Collect the stack elements, top first, converting all elements to strings, and filtering out
   * adjacent duplicates.
   */
  public ImmutableList<String> collectStringsFilterAdjacentDupes() {
    ImmutableList.Builder<String> builder = ImmutableList.builder();

    DependencyStack next = this;
    String prevString = null;
    while (next.parent != null) {
      String string = Objects.toString(next.where);
      if (prevString == null || !prevString.equals(string)) {
        builder.add(string);
      }
      prevString = string;
      next = next.parent;
    }

    return builder.build();
  }

  // All DependencyStack objects are equal to each other to avoid accidental
  // errors when DependencyStack objects are accidentally a field of data class.

  @Override
  public boolean equals(Object o) {
    return o instanceof DependencyStack;
  }

  @Override
  public int hashCode() {
    return 0;
  }

  @Override
  public String toString() {
    return "[" + Joiner.on(", ").join(collect()) + "]";
  }
}
