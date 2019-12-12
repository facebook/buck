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

package com.facebook.buck.remoteexecution.util;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.support.cli.config.AliasConfig;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;

/** Auxiliary utils to be used for starting/configuring/annotating etc. RE builds. */
public class RemoteExecutionUtil {

  // Converts '//project/subdir:target' or '//project:target' into '//project'
  private static final String TARGET_TO_PROJECT_REGREX = "(//.*?)[/|:].*";

  private RemoteExecutionUtil() {}

  /** Checks whether the given target command line arguments match the RE project whitelist */
  public static boolean doTargetsMatchProjectWhitelist(
      List<String> commandArgs, ImmutableSet<String> projectWhitelist, BuckConfig buckConfig) {
    ImmutableSet<String> buildTargets = getBuildTargets(commandArgs, buckConfig);
    return doTargetsMatchProjectWhitelist(buildTargets, projectWhitelist);
  }

  /**
   * Given a list of targets, determines if they all share a common prefix, and if they do then
   * returns it. E.g. for targets '//project_one:a', '//project_one:b', should return
   * '//project_one'
   */
  public static Optional<String> getCommonProjectPrefix(
      List<String> commandArgs, BuckConfig buckConfig) {
    ImmutableSet<String> buildTargets = getBuildTargets(commandArgs, buckConfig);

    return getCommonProjectPrefix(buildTargets);
  }

  @VisibleForTesting
  static Optional<String> getCommonProjectPrefix(ImmutableSet<String> buildTargets) {
    Optional<String> commonPrefix = Optional.empty();

    for (String buildTarget : buildTargets) {
      Optional<String> prefix = getTargetPrefix(buildTarget);
      if (!prefix.isPresent()) {
        // This target did not have a prefix, so there is no common prefix
        return Optional.empty();
      }
      if (!commonPrefix.isPresent()) {
        // This is the first target, all others should match
        commonPrefix = prefix;
      } else if (!commonPrefix.equals(prefix)) {
        // Mismatch was found
        return Optional.empty();
      }
    }

    // There was a common prefix (or buildTargets Set was empty).
    return commonPrefix;
  }

  private static Optional<String> getTargetPrefix(String target) {
    Pattern pattern = Pattern.compile(TARGET_TO_PROJECT_REGREX);
    Matcher matcher = pattern.matcher(target);

    if (matcher.find()) {
      // Check the project for the given target is whitelisted
      return Optional.of(matcher.group(1));
    }

    // TODO(alisdair): can this ever happen? Throw an exception if not.
    return Optional.empty();
  }

  @Nonnull
  private static ImmutableSet<String> getBuildTargets(
      List<String> commandArgs, BuckConfig buckConfig) {
    ImmutableSet.Builder<String> buildTargets = new ImmutableSet.Builder<>();
    for (String commandArg : commandArgs) {
      ImmutableSet<String> buildTargetForAliasAsString =
          AliasConfig.from(buckConfig).getBuildTargetForAliasAsString(commandArg);
      if (buildTargetForAliasAsString.size() > 0) {
        buildTargets.addAll(buildTargetForAliasAsString);
      } else {
        // Target was not an alias
        if (!commandArg.startsWith("//")) {
          commandArg = "//" + commandArg;
        }

        buildTargets.add(commandArg);
      }
    }
    return buildTargets.build();
  }

  /** Checks whether the given targets match the RE project whitelist */
  protected static boolean doTargetsMatchProjectWhitelist(
      ImmutableSet<String> buildTargets, ImmutableSet<String> projectWhitelist) {
    if (buildTargets.isEmpty()) {
      return false;
    }
    boolean mismatchFound = false;
    for (String buildTarget : buildTargets) {
      Pattern pattern = Pattern.compile(TARGET_TO_PROJECT_REGREX);
      Matcher matcher = pattern.matcher(buildTarget);

      if (matcher.find()) {
        // Check the project for the given target is whitelisted
        String projectForTarget = matcher.group(1);
        mismatchFound = !projectWhitelist.contains(projectForTarget);
      } else {
        mismatchFound = true;
      }

      if (mismatchFound) {
        break;
      }
    }

    return !mismatchFound;
  }
}
