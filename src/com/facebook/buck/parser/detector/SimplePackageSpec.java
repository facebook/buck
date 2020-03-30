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

package com.facebook.buck.parser.detector;

import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.CellRelativePath;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.path.ForwardRelativePath;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Simple version of package spec parser and patcher. Somewhat similar to {@link
 * com.facebook.buck.parser.spec.TargetNodePredicateSpec} but does not use {@link Path} objects,
 * only target label.
 */
class SimplePackageSpec {
  private final CellRelativePath prefix;

  public SimplePackageSpec(CellRelativePath prefix) {
    this.prefix = prefix;
  }

  boolean matches(UnconfiguredBuildTarget target) {
    return target.getCellRelativeBasePath().startsWith(prefix);
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

    ForwardRelativePath basePath;
    if (split[1].equals("...")) {
      basePath = ForwardRelativePath.EMPTY;
    } else if (split[1].endsWith("/...")) {
      basePath = ForwardRelativePath.of(split[1].substring(0, split[1].length() - "/...".length()));
    } else {
      throw new HumanReadableException("package spec must end with ...: '%s'", spec);
    }

    CanonicalCellName cell =
        cellNameResolver.getName(Optional.of(cellName).filter(s -> !s.isEmpty()));
    return new SimplePackageSpec(CellRelativePath.of(cell, basePath));
  }

  @Override
  public String toString() {
    return prefix + (prefix.getPath().isEmpty() ? "" : "/") + "...";
  }
}
