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

import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.util.Scope;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.lang.reflect.Field;
import java.util.Optional;

/** A ValueVisitor that can be used to construct a String representation of an object. */
public class StringifyingValueVisitor implements ValueVisitor<RuntimeException> {
  private StringBuilder builder = new StringBuilder();
  private int indent = 0;

  @Override
  public void visitOutputPath(OutputPath value) {
    append(
        "OutputPath(%s)", MorePaths.pathWithUnixSeparators(OutputPath.internals().getPath(value)));
  }

  @Override
  public void visitSourcePath(SourcePath value) {
    append("SourcePath(%s)", value.toString().replace('\\', '/'));
  }

  @Override
  public <T> void visitField(Field field, T value, ValueTypeInfo<T> valueTypeInfo) {
    append("%s:", field.getName());
    valueTypeInfo.visit(value, this);
    newline();
  }

  @Override
  public <T> void visitSet(ImmutableSortedSet<T> value, ValueTypeInfo<T> innerType) {
    container(
        "Set",
        () -> {
          for (T e : value) {
            newline();
            innerType.visit(e, this);
          }
        });
  }

  @Override
  public <T> void visitList(ImmutableList<T> value, ValueTypeInfo<T> innerType) {
    container(
        "List",
        () -> {
          for (T e : value) {
            newline();
            innerType.visit(e, this);
          }
        });
  }

  @Override
  public <T> void visitOptional(Optional<T> value, ValueTypeInfo<T> innerType) {
    if (value.isPresent()) {
      container(
          "Optional",
          () -> {
            newline();
            innerType.visit(value.get(), this);
          });
    } else {
      append("Optional.empty()");
    }
  }

  @Override
  public void visitSimple(Object value) {
    append("value(%s)", value);
  }

  private void container(String label, Runnable runner) {
    append("%s<", label);
    indent++;
    try (Scope ignored =
        () -> {
          indent--;
          newline();
          append(">");
        }) {
      runner.run();
    }
  }

  private void append(String value) {
    builder.append(value);
  }

  private void append(String format, Object... args) {
    builder.append(String.format(format, args));
  }

  private void newline() {
    append("\n");
    for (int i = 0; i < indent; i++) {
      append("  ");
    }
  }

  public String getValue() {
    return builder.toString();
  }
}
