/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.cli;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.config.AliasConfig;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.file.MorePaths.PathExistResult;
import com.facebook.buck.io.file.MorePaths.PathExistResultWrapper;
import com.facebook.buck.parser.BuildTargetPatternTargetNodeParser;
import com.facebook.buck.parser.TargetNodeSpec;
import com.facebook.buck.support.cli.args.BuckCellArg;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

public class CommandLineTargetNodeSpecParser {

  private final BuckConfig config;
  private final BuildTargetPatternTargetNodeParser parser;

  public CommandLineTargetNodeSpecParser(
      BuckConfig config, BuildTargetPatternTargetNodeParser parser) {
    this.config = config;
    this.parser = parser;
  }

  @VisibleForTesting
  protected String normalizeBuildTargetString(String target) {
    // Check and save the cell name
    BuckCellArg arg = BuckCellArg.of(target);
    target = arg.getArg();

    // Look up the section after the colon, if present, and strip it off.
    int colonIndex = target.indexOf(':');
    Optional<String> nameAfterColon = Optional.empty();
    if (colonIndex != -1) {
      nameAfterColon = Optional.of(target.substring(colonIndex + 1));
      target = target.substring(0, colonIndex);
    }

    // Strip trailing slashes in the directory name part.
    while (target.endsWith("/")) {
      target = target.substring(0, target.length() - 1);
    }

    // If no colon was specified and we're not dealing with a trailing "...", we'll add in the
    // missing colon and fill in the missing rule name with the basename of the directory.
    if (!nameAfterColon.isPresent() && !target.endsWith("/...") && !target.equals("...")) {
      int lastSlashIndex = target.lastIndexOf('/');
      if (lastSlashIndex == -1) {
        nameAfterColon = Optional.of(target);
      } else {
        nameAfterColon = Optional.of(target.substring(lastSlashIndex + 1));
      }
    }

    // Now add in the name after the colon if there was one.
    if (nameAfterColon.isPresent()) {
      target += ":" + nameAfterColon.get();
    }

    return arg.getCellName().orElse("") + "//" + target;
  }

  /**
   * Validates a {@code spec} and throws an exception for invalid ones.
   *
   * <p>Ideally validation should happen as part of spec creation and some of them actually happen,
   * but others, especially those that require filesystem interactions, are too expensive to carry
   * for every single build target.
   */
  private void validateTargetSpec(TargetNodeSpec spec, String buildTarget) {
    Path cellPath = spec.getBuildFileSpec().getCellPath();
    Path basePath = spec.getBuildFileSpec().getBasePath();
    Path buildTargetPath = cellPath.resolve(basePath);
    try {
      PathExistResultWrapper pathExist =
          MorePaths.pathExistsCaseSensitive(buildTargetPath, cellPath);
      if (PathExistResult.NOT_EXIST == pathExist.getResult()) {
        throw new HumanReadableException(
            "%s references non-existent directory %s", buildTarget, basePath);
      }

      if (PathExistResult.EXIST_CASE_MISMATCHED == pathExist.getResult()) {
        throw new HumanReadableException(
            "The case of the build path provided (%s) does not match the actual path. "
                + "This is an issue even on case-insensitive file systems. "
                + "Please check the spelling of the provided path.",
            buildTargetPath);
      }
    } catch (IOException e) {
      throw new HumanReadableException(e, e.getMessage());
    }
  }

  public ImmutableSet<TargetNodeSpec> parse(CellPathResolver cellNames, String arg) {
    ImmutableSet<String> resolvedArgs =
        AliasConfig.from(config).getBuildTargetForAliasAsString(arg);
    if (resolvedArgs.isEmpty()) {
      resolvedArgs = ImmutableSet.of(arg);
    }
    ImmutableSet.Builder<TargetNodeSpec> specs = new ImmutableSet.Builder<>();
    for (String resolvedArg : resolvedArgs) {
      String buildTarget = normalizeBuildTargetString(resolvedArg);
      TargetNodeSpec spec = parser.parse(cellNames, buildTarget);
      validateTargetSpec(spec, resolvedArg);
      specs.add(spec);
    }
    return specs.build();
  }
}
