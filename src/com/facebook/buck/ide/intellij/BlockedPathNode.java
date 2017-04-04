/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.ide.intellij;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import javax.annotation.Nullable;

class BlockedPathNode {
  private static final Optional<BlockedPathNode> EMPTY_CHILD = Optional.empty();

  private boolean isBlocked;

  // The key is a path component to allow traversing down a hierarchy
  // to find blocks rather than doing simple path comparison.
  @Nullable
  private Map<Path, BlockedPathNode> children;

  BlockedPathNode() {
    this.isBlocked = false;
  }

  void putChild(Path path, BlockedPathNode node) {
    if (children == null) {
      children = new HashMap<Path, BlockedPathNode>();
    }
    children.put(path, node);
  }

  private Optional<BlockedPathNode> getChild(Path path) {
    return children == null ? EMPTY_CHILD : Optional.ofNullable(children.get(path));
  }

  private void clearAllChildren() {
    children = null;
  }

  void markAsBlocked(Path path, int currentIdx, int pathNameCount) {
    if (currentIdx == pathNameCount) {
      isBlocked = true;
      clearAllChildren();
      return;
    }

    Path component = path.getName(currentIdx);
    Optional<BlockedPathNode> blockedPathNodeOptional = getChild(component);
    BlockedPathNode blockedPathNode;

    if (blockedPathNodeOptional.isPresent()) {
      blockedPathNode = blockedPathNodeOptional.get();
      if (blockedPathNode.isBlocked) {
        return;
      }
    } else {
      blockedPathNode = new BlockedPathNode();
      putChild(component, blockedPathNode);
    }

    blockedPathNode.markAsBlocked(path, ++currentIdx, pathNameCount);
  }

  int findLowestPotentialBlockedOnPath(Path path, int currentIdx, int pathNameCount) {
    if (isBlocked || currentIdx == pathNameCount) {
      return currentIdx;
    }

    Path thisComponent = path.getName(currentIdx);
    Optional<BlockedPathNode> nextNode = getChild(thisComponent);
    if (nextNode.isPresent()) {
      return nextNode.get().findLowestPotentialBlockedOnPath(path, ++currentIdx, pathNameCount);
    }

    return currentIdx;
  }
}
