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

package com.facebook.buck.skylark.parser;

import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.syntax.Location;

/** Stack of load statements in .bzl files. */
abstract class LoadStack {
  public static final LoadStack EMPTY = new Empty();

  private static class Empty extends LoadStack {}

  private static class Child extends LoadStack {
    private final LoadStack parent;
    private final Location location;

    public Child(LoadStack parent, Location location) {
      this.parent = parent;
      this.location = location;
    }
  }

  private boolean contains(Location location) {
    LoadStack stack = this;
    while (stack instanceof Child) {
      if (((Child) stack).location.file().equals(location.file())) {
        return true;
      }
      stack = ((Child) stack).parent;
    }
    return false;
  }

  public LoadStack child(Location path) {
    Child child = new Child(this, path);

    // This operation is linerar of dependency stack size, but the stack is rarely deep
    if (contains(path)) {
      throw new BuildFileParseException(
          child.toDependencyStack(), String.format("Load cycle while loading %s", path));
    }

    return child;
  }

  public static LoadStack top(Location path) {
    return EMPTY.child(path);
  }

  private ImmutableList<Location> toList() {
    ImmutableList.Builder<Location> list = ImmutableList.builder();
    LoadStack stack = this;
    while (stack instanceof Child) {
      list.add(((Child) stack).location);
      stack = ((Child) stack).parent;
    }
    return list.build();
  }

  public DependencyStack toDependencyStack() {
    DependencyStack dependencyStack = DependencyStack.root();
    for (Location path : toList().reverse()) {
      dependencyStack = dependencyStack.child(path.toString());
    }
    return dependencyStack;
  }

  @Override
  public String toString() {
    return toList().toString();
  }
}
