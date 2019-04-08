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
package com.facebook.buck.core.parser.buildtargetparser;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.model.UnconfiguredBuildTargetView;
import java.nio.file.Path;

/** Creates {@link UnconfiguredBuildTargetView} from a raw string. */
public interface UnconfiguredBuildTargetFactory {

  /**
   * Given a fully-qualified target name returns {@link UnconfiguredBuildTargetView}.
   *
   * <p>A fully-qualified target name is the target name that uniquely identifies the target.
   *
   * <p>Note that the cell name of the result build target can be different from the cell name
   * specified in the target name. The build target contains a {@link
   * CellPathResolver#getCanonicalCellName(Path) canonical cell name}.
   *
   * @see #createForBaseName(CellPathResolver, String, String) for more information about other
   *     types of target names.
   */
  UnconfiguredBuildTargetView create(CellPathResolver cellPathResolver, String buildTargetName);

  /**
   * Given a target base name and a target name returns {@link UnconfiguredBuildTargetView}.
   *
   * <p>The target name may either be a fully-qualified name or a relative name. {@code baseName} is
   * used when a relative name is given to correctly resolve the name of the target.
   *
   * <p>For example, {@code //java/com/company/org:org} is a fully-qualified name. The same target
   * is represented by a relative name {@code org} with base name {@code java/com/company/org}.
   *
   * <p>Note that the cell name of the result build target can be different from the cell name
   * specified in the target name. The build target contains a {@link
   * CellPathResolver#getCanonicalCellName(Path) canonical cell name}.
   */
  UnconfiguredBuildTargetView createForBaseName(
      CellPathResolver cellPathResolver, String baseName, String buildTargetName);

  /**
   * Given a target base name and a target name returns {@link UnconfiguredBuildTargetView} with
   * optionally allowing the short name to be empty.
   *
   * <p>The target name may either be a fully-qualified name or a target name pattern.
   *
   * <p>For example, {@code //java/com/company/org:org} is a fully-qualified name. {@code
   * //java/com/company/org:} is an example of a target with a pattern.
   *
   * <p>Note that the cell name of the result build target can be different from the cell name
   * specified in the target name. The build target contains a {@link
   * CellPathResolver#getCanonicalCellName(Path) canonical cell name}.
   */
  UnconfiguredBuildTargetView createWithWildcard(
      CellPathResolver cellPathResolver, String buildTargetName);
}
