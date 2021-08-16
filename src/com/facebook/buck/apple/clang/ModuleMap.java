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

package com.facebook.buck.apple.clang;

import com.google.common.base.Objects;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Class to create module header tree for rendering. */
class Module {
  private Path name;
  private List<Path> headers;
  private Map<Path, Module> submodules;
  private List<String> requirements;
  private boolean exportAsterisk;

  Module(Path name) {
    this.name = name;
    this.submodules = new HashMap<>();
    this.headers = new ArrayList<>();
    this.requirements = new ArrayList<>();
    this.exportAsterisk = true;
  }

  Path getName() {
    return name;
  }

  Module getSubmodule(Path name) {
    Module submodule = submodules.get(name);
    if (submodule == null) {
      submodule = new Module(name);
      submodules.put(name, submodule);
    }
    return submodule;
  }

  void addHeader(Path header) {
    headers.add(header);
  }

  void addRequirement(String requirement) {
    requirements.add(requirement);
  }

  void setExportAsterisk(boolean exportAsterisk) {
    this.exportAsterisk = exportAsterisk;
  }

  void render(StringBuilder s, int level) {
    indent(s, level).append("module ").append(name).append(" {\n");

    // Module names can only include letters, numbers and underscores, and may not start with a
    // number.
    HashSet<String> submoduleNames = new HashSet<>();
    submodules.keySet().stream()
        .sorted()
        .forEach(
            name -> {
              Module submodule = submodules.get(name);
              String sanitized = name.toString();

              // A leaf submodule name is a filename, remove its extension for readability
              if (submodule.submodules.isEmpty()) {
                int extensionIndex = sanitized.lastIndexOf('.');
                if (extensionIndex > 0) {
                  sanitized = sanitized.substring(0, extensionIndex);
                }
              }

              sanitized = sanitized.replaceAll("[^A-Za-z0-9_]", "_");
              if (Character.isDigit(sanitized.charAt(0))) {
                sanitized = "_" + sanitized;
              }

              // Its possible to have collisions either from matching filenames with different
              // extensions or with matching names with differing invalid characters. Keep adding
              // underscores until we have a unique module name.
              while (submoduleNames.contains(sanitized)) {
                sanitized += "_";
              }
              submoduleNames.add(sanitized);
              submodule.name = Paths.get(sanitized);
              submodule.render(s, level + 1);
            });

    if (!headers.isEmpty()) {
      for (Path header : headers) {
        indent(s, level + 1).append("header \"").append(header).append("\"\n");
      }

      if (exportAsterisk) {
        indent(s, level + 1).append("export *\n");
      }
    }

    for (String requireFacet : requirements) {
      indent(s, level + 1).append("requires ").append(requireFacet).append("\n");
    }

    indent(s, level).append("}\n");
  }

  private StringBuilder indent(StringBuilder s, int level) {
    for (int i = 0; i < level; i++) {
      s.append("\t");
    }
    return s;
  }
}

/**
 * Creates a modulemap file that uses an explicit `header` declaration for each header in the
 * module.
 */
public class ModuleMap {
  private String moduleName;
  private Set<Path> headers;
  private Optional<Path> swiftHeader;
  private boolean useSubmodules;
  private boolean requiresCplusplus;

  ModuleMap(
      String moduleName,
      Set<Path> headers,
      Optional<Path> swiftHeader,
      boolean useSubmodules,
      boolean requiresCplusplus) {
    this.moduleName = moduleName;
    this.headers = headers;
    this.swiftHeader = swiftHeader;
    this.useSubmodules = useSubmodules;
    this.requiresCplusplus = requiresCplusplus;
  }

  /**
   * Creates a module map.
   *
   * @param moduleName The name of the module.
   * @param headerPaths The exported headers of the module.
   * @param swiftHeader The path to the optional `-Swift.h` header. It will create the Swift
   *     submodule if provided.
   * @param requiresCplusplus Whether or not to include "requires cplusplus" in the modulemap.
   * @return A module map instance.
   */
  public static ModuleMap create(
      String moduleName,
      Set<Path> headerPaths,
      Optional<Path> swiftHeader,
      boolean useSubmodules,
      boolean requiresCplusplus) {
    return new ModuleMap(moduleName, headerPaths, swiftHeader, useSubmodules, requiresCplusplus);
  }

  /**
   * Creates a module map.
   *
   * @param moduleName The name of the module.
   * @param headerPaths The exported headers of the module.
   * @param swiftHeader The path to the optional `-Swift.h` header. It will create the Swift
   *     submodule if provided.
   * @return A module map instance.
   */
  public static ModuleMap create(
      String moduleName, Set<Path> headerPaths, Optional<Path> swiftHeader, boolean useSubmodules) {
    return ModuleMap.create(moduleName, headerPaths, swiftHeader, useSubmodules, false);
  }

  /**
   * Renders the modulemap to a string, to be written to a .modulemap file.
   *
   * @return A string representation of the modulemap.
   */
  public String render() {
    StringBuilder s = new StringBuilder();
    if (useSubmodules) {
      renderSubmodules(s);
    } else {
      renderSingleModule(s);
    }

    if (swiftHeader.isPresent()) {
      s.append("\n");

      Module m = new Module(Paths.get(moduleName + ".Swift"));
      m.addHeader(swiftHeader.get());
      m.addRequirement("objc");
      m.setExportAsterisk(false);
      m.render(s, 0);
    }

    return s.toString();
  }

  private void renderSubmodules(StringBuilder s) {
    // Create a tree of nested Module, one for each path component.
    Module rootModule = new Module(Paths.get(moduleName));
    for (Path header : headers) {
      Module module = rootModule;
      for (int i = 0; i < header.getNameCount(); i++) {
        Path component = header.getName(i);
        if (i == 0 && component.equals(rootModule.getName())) {
          // The common case is we have a single header path prefix that matches the module name.
          // In that case add the headers directly to the top level module.
          continue;
        }
        module = module.getSubmodule(component);
      }
      module.addHeader(header);
    }

    if (requiresCplusplus) {
      rootModule.addRequirement("cplusplus");
    }

    rootModule.render(s, 0);
  }

  private void renderSingleModule(StringBuilder s) {
    Module m = new Module(Paths.get(moduleName));
    headers.stream().sorted().forEach(p -> m.addHeader(p));
    if (requiresCplusplus) {
      m.addRequirement("cplusplus");
    }

    m.render(s, 0);
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof ModuleMap)) {
      return false;
    }

    ModuleMap other = (ModuleMap) obj;
    return useSubmodules == other.useSubmodules
        && requiresCplusplus == other.requiresCplusplus
        && Objects.equal(moduleName, other.moduleName)
        && Objects.equal(swiftHeader, other.swiftHeader)
        && Objects.equal(headers, other.headers);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(moduleName, headers, swiftHeader, requiresCplusplus);
  }
}
