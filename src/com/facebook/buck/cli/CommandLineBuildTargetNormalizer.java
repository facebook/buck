/*
 * Copyright 2012-present Facebook, Inc.
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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.collect.Lists;

import java.util.List;

/**
 * In actual build files, Buck requires that build targets are well-formed, which means that they
 * must start with a "//", followed by a path, and ending with the name of a build rule, which must
 * be preceded by a colon. Although these are easy for Buck to parse, they are not always easy to
 * type from the command line. For this reason, we are more lenient with the format of build targets
 * when entered from the command line, keeping in mind that one of the easiest things for a user to
 * do is tab-complete file paths. For this reason, the "//" and ":" are added, when appropriate. For
 * example, if the following argument were specified on the command line when a build target was
 * expected:
 * <pre>
 * src/com/facebook/orca
 * </pre>
 * then this normalizer would convert it to:
 * <pre>
 * //src/com/facebook/orca:orca
 * </pre>
 * It would also normalize it to the same thing if it contained a trailing slash:
 * <pre>
 * src/com/facebook/orca
 * </pre>
 * Similarly, if the argument were:
 * <pre>
 * src/com/facebook/orca:messenger
 * </pre>
 * then this normalizer would convert it to:
 * <pre>
 * //src/com/facebook/orca:messenger
 * </pre>
 * This makes it easier to tab-complete to the directory with the desired build target, and then
 * append the name of the build target by typing it out. Note that because of how the normalizer
 * works, it makes sense to name the most commonly built target in the package as the same name as
 * the directory that contains it so that the entire target can be tab-completed when entered from
 * the command line.
 */
class CommandLineBuildTargetNormalizer {

  private final Function<String, String> normalizer;

  CommandLineBuildTargetNormalizer(final BuckConfig buckConfig) {
    this.normalizer = new Function<String, String>() {
      @Override
      public String apply(String arg) {
        String aliasValue = buckConfig.getBuildTargetForAlias(arg);
        if (aliasValue != null) {
          return aliasValue;
        } else {
          return normalizeBuildTargetIdentifier(arg);
        }
      }
    };
  }

  public String normalize(String argument) {
    return normalizer.apply(argument);
  }

  public List<String> normalizeAll(List<String> arguments) {
    // When transforming command-line arguments, first check to see whether it is an alias in the
    // BuckConfig. If so, return the value associated with the alias. Otherwise, try normalize().
    return Lists.transform(arguments, normalizer);
  }

  @VisibleForTesting
  static String normalizeBuildTargetIdentifier(final String buildTargetFromCommandLine) {

    // Build rules in the root are weird, but they do happen. Special-case them.
    if (buildTargetFromCommandLine.startsWith("//:")) {
      return buildTargetFromCommandLine;
    }

    // Add the colon, if necessary.
    String target = buildTargetFromCommandLine;
    int colonIndex = target.indexOf(':');
    if (colonIndex < 0) {
      // Strip a trailing slash if there is no colon.
      if (target.endsWith("/")) {
        target = target.substring(0, target.length() - 1);
      }

      int lastSlashIndex = target.lastIndexOf('/');
      if (lastSlashIndex < 0) {
        target = target + ':' + target;
      } else {
        target = target + ':' + target.substring(lastSlashIndex + 1);
      }
    } else if (colonIndex != 0) {
      char charBeforeColon = target.charAt(colonIndex - 1);
      if (charBeforeColon == '/') {
        target = target.substring(0, colonIndex - 1) + target.substring(colonIndex);
      }
    }

    // Add the double slash prefix, if necessary.
    if (!target.startsWith("//")) {
      target = "//" + target;
    }

    return target;
  }
}
