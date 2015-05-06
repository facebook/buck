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

import com.facebook.buck.parser.BuildTargetPatternTargetNodeParser;
import com.facebook.buck.parser.TargetNodeSpec;
import com.facebook.buck.util.MoreStrings;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;

public class CommandLineTargetNodeSpecParser {

  private final BuckConfig config;
  private final BuildTargetPatternTargetNodeParser parser;

  public CommandLineTargetNodeSpecParser(
      BuckConfig config,
      BuildTargetPatternTargetNodeParser parser) {
    this.config = config;
    this.parser = parser;
  }

  @VisibleForTesting
  protected String normalizeBuildTargetString(String target) {

    // Strip out the leading "//" if there is one to make it easier to normalize the
    // remaining target string.  We'll add this back at the end.
    target = MoreStrings.stripPrefix(target, "//").or(target);

    // Look up the section after the colon, if present, and strip it off.
    int colonIndex = target.indexOf(':');
    Optional<String> nameAfterColon = Optional.absent();
    if (colonIndex != -1) {
      nameAfterColon = Optional.of(target.substring(colonIndex + 1, target.length()));
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

    return "//" + target;
  }

  public TargetNodeSpec parse(String arg) {
    arg = Optional.fromNullable(config.getBuildTargetForAlias(arg)).or(arg);
    arg = normalizeBuildTargetString(arg);
    return parser.parse(arg);
  }

}
