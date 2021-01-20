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

package com.facebook.buck.util.config;

import com.facebook.buck.core.path.ForwardRelativePath;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Models a boolean which applies recursively to path components. */
public class RecursivePathSetting<T> {

  private final Node<T> root;

  private RecursivePathSetting(Node<T> root) {
    this.root = root;
  }

  /** Check if the given {@code path} component is enabled. */
  public Optional<T> get(ForwardRelativePath path) {
    Node<T> node = root;
    T value = node.value;
    for (int index = 0; index < path.getNameCount(); index++) {
      node = node.map.get(path.getSegment(index));
      if (node == null) {
        break;
      }
      value = node.value;
    }
    return Optional.ofNullable(value);
  }

  private static class Node<T> {
    Map<String, Node<T>> map = new HashMap<>();
    T value = null;
  }

  public static <T> Builder<T> builder() {
    return new Builder<>();
  }

  /** Builder for {@link RecursivePathSetting}. */
  public static class Builder<T> {

    private final Node<T> root = new Node<>();

    private Builder() {}

    /** Enable, recursively, for the given {@code path} component. */
    public Builder<T> set(ForwardRelativePath path, T value) {
      Node<T> node = root;
      for (int index = 0; index < path.getNameCount(); index++) {
        node = node.map.computeIfAbsent(path.getSegment(index), e -> new Node<>());
      }
      node.value = value;
      return this;
    }

    public RecursivePathSetting<T> build() {
      return new RecursivePathSetting<>(root);
    }
  }
}
