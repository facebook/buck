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
package com.facebook.buck.parser.implicit;

import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Given a configuration, find packages that should be implicitly included for a given build file
 */
public class PackageImplicitIncludesFinder {

  private final IncludeNode node;

  /**
   * Simple class that contains mappings to implicit includes that should be used for a given
   * package (and all subpackages), and mappings to other includes that should be used for further
   * nested subpackages.
   */
  private static class IncludeNode {
    final ImmutableMap<Path, IncludeNode> childPackages;
    final Optional<ImplicitInclude> include;

    IncludeNode(ImmutableMap<Path, IncludeNode> childPackages, Optional<ImplicitInclude> include) {
      this.childPackages = childPackages;
      this.include = include;
    }

    /**
     * Gets the include for the deepest matching path of the tree.
     *
     * <p>If there are settings for foo, and foo/bar, then foo/bar/baz should return the settings
     * for foo/bar. If not_foo is specified, then the root settings should be returned.
     */
    Optional<ImplicitInclude> findIncludeForPath(Path packagePath) {
      IncludeNode currentNode = this;
      Optional<ImplicitInclude> lastPresentInclude = currentNode.include;
      for (Path component : packagePath) {
        IncludeNode childNode = currentNode.childPackages.get(component);
        if (childNode == null) {
          return lastPresentInclude;
        } else {
          if (childNode.include.isPresent()) {
            lastPresentInclude = childNode.include;
          }
          currentNode = childNode;
        }
      }
      return lastPresentInclude;
    }
  }

  /** Builder class to create {@link IncludeNode} instances */
  private static class IncludeNodeBuilder {
    private Map<Path, IncludeNodeBuilder> nodes = new HashMap<>();
    private Optional<ImplicitInclude> include = Optional.empty();

    /** Creates a node at a subpath, or returns the existing builder */
    IncludeNodeBuilder getOrCreateIncludeNodeForPath(Path path) {
      return nodes.computeIfAbsent(path, p -> new IncludeNodeBuilder());
    }

    void setInclude(ImplicitInclude include) {
      this.include = Optional.of(include);
    }

    IncludeNode build() {
      return new IncludeNode(
          nodes.entrySet().stream()
              .collect(ImmutableMap.toImmutableMap(Entry::getKey, e -> e.getValue().build())),
          include);
    }
  }

  private PackageImplicitIncludesFinder(ImmutableMap<String, ImplicitInclude> packageToInclude) {
    IncludeNodeBuilder rootBuilder = new IncludeNodeBuilder();
    for (Map.Entry<String, ImplicitInclude> entry : packageToInclude.entrySet()) {
      Path path = Paths.get(entry.getKey());
      IncludeNodeBuilder currentBuilder = rootBuilder;
      for (Path component : path) {
        if (component.toString().isEmpty()) {
          continue;
        }
        currentBuilder = currentBuilder.getOrCreateIncludeNodeForPath(component);
      }
      currentBuilder.setInclude(entry.getValue());
    }
    node = rootBuilder.build();
  }

  /**
   * Create a {@link PackageImplicitIncludesFinder} from configuration in .buckconfig
   *
   * @param packageToInclude A mapping of package paths to file that should be included
   * @return A {@link PackageImplicitIncludesFinder} instance
   */
  public static PackageImplicitIncludesFinder fromConfiguration(
      ImmutableMap<String, ImplicitInclude> packageToInclude) {
    return new PackageImplicitIncludesFinder(packageToInclude);
  }

  /**
   * Find the file (if any) that should be included implicitly for a given build file path
   *
   * @param packagePath The path to a package relative to the cell root.
   * @return The path to a file and symbols that should be implicitly included for the given
   *     package.
   */
  public Optional<ImplicitInclude> findIncludeForBuildFile(@Nullable Path packagePath) {
    if (packagePath == null) {
      // TODO(buck_team): remove @Nullable and this codepath
      packagePath = Paths.get("");
    }
    return node.findIncludeForPath(packagePath);
  }
}
