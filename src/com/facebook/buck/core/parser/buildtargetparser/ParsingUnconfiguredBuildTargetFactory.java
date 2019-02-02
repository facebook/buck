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
import com.facebook.buck.core.model.UnconfiguredBuildTarget;

/** A factory that parses a given build target name using the provided {@link BuildTargetParser}. */
public class ParsingUnconfiguredBuildTargetFactory implements UnconfiguredBuildTargetFactory {

  private final BuildTargetParser buildTargetParser = BuildTargetParser.INSTANCE;

  @Override
  public UnconfiguredBuildTarget create(CellPathResolver cellPathResolver, String buildTargetName) {
    return buildTargetParser.parse(cellPathResolver, buildTargetName, "", false);
  }

  @Override
  public UnconfiguredBuildTarget createForBaseName(
      CellPathResolver cellPathResolver, String baseName, String buildTargetName) {
    return buildTargetParser.parse(cellPathResolver, buildTargetName, baseName, false);
  }

  @Override
  public UnconfiguredBuildTarget createWithWildcard(
      CellPathResolver cellPathResolver, String buildTargetName) {
    return buildTargetParser.parse(cellPathResolver, buildTargetName, "", true);
  }
}
