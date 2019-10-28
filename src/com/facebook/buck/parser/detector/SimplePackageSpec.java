/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.parser.detector;

import com.facebook.buck.core.cell.CellNameResolver;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.CanonicalCellName;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.parser.buildtargetparser.BaseNameParser;
import com.google.common.base.Preconditions;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Simple version of package spec parser and patcher. Somewhat similar to {@link
 * com.facebook.buck.parser.spec.TargetNodePredicateSpec} but does not use {@link Path} objects,
 * only target label.
 */
class SimplePackageSpec {
  private final CanonicalCellName cell;
  private final String baseNamePrefix;

  SimplePackageSpec(CanonicalCellName cell, String baseNamePrefix) {
    this.cell = cell;
    this.baseNamePrefix = baseNamePrefix;

    // self-check
    Preconditions.checkState(baseNamePrefix.startsWith("//"));
    Preconditions.checkState(baseNamePrefix.equals("//") || !baseNamePrefix.endsWith("/"));
  }

  boolean matches(UnconfiguredBuildTarget target) {
    if (target.getCell() != cell) {
      return false;
    }

    if (!target.getBaseName().startsWith(baseNamePrefix)) {
      return false;
    }

    if (baseNamePrefix.equals("//")) {
      return true;
    } else {
      return target.getBaseName().length() == baseNamePrefix.length()
          || target.getBaseName().charAt(baseNamePrefix.length()) == '/';
    }
  }

  // TODO(nga): this parser is similar BuildTargetMatcherParser,
  //            but relies on target labels instead of filesystem paths.
  //            We should merge these.
  static SimplePackageSpec parse(String spec, CellNameResolver cellNameResolver) {
    String[] split = spec.split("//");
    if (split.length != 2) {
      throw new HumanReadableException("package spec must have exactly one '//': '%s'", spec);
    }

    String cellName = split[0];

    String basePath;
    if (split[1].equals("...")) {
      basePath = "//";
    } else if (split[1].endsWith("/...")) {
      basePath = "//" + split[1].substring(0, split[1].length() - "/...".length());
    } else {
      throw new HumanReadableException("package spec must end with ...: '%s'", spec);
    }

    BaseNameParser.checkBaseName(basePath, spec);

    CanonicalCellName cell =
        cellNameResolver.getName(Optional.of(cellName).filter(s -> !s.isEmpty()));
    return new SimplePackageSpec(cell, basePath);
  }

  @Override
  public String toString() {
    return cell + baseNamePrefix + (baseNamePrefix.isEmpty() ? "" : "/") + "...";
  }
}
