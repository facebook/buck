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

package com.facebook.buck.support.cli.args;

import com.google.common.collect.ImmutableSet;
import org.kohsuke.args4j.NamedOptionDef;
import org.kohsuke.args4j.OptionDef;
import org.kohsuke.args4j.spi.OptionHandler;

/** Contains CLI options that are common to all of the commands. */
public class GlobalCliOptions {

  public static final String HELP_LONG_ARG = "--help";
  public static final String NO_CACHE_LONG_ARG = "--no-cache";
  public static final String OUTPUT_TEST_EVENTS_TO_FILE_LONG_ARG = "--output-test-events-to-file";
  public static final String PROFILE_PARSER_LONG_ARG = "--profile-buck-parser";
  public static final String NUM_THREADS_LONG_ARG = "--num-threads";
  public static final String CONFIG_LONG_ARG = "--config";
  public static final String CONFIG_FILE_LONG_ARG = "--config-file";
  public static final String SKYLARK_PROFILE_LONG_ARG = "--skylark-profile-output";
  public static final String VERBOSE_LONG_ARG = "--verbose";
  public static final String VERBOSE_SHORT_ARG = "-v";
  public static final String TARGET_PLATFORMS_LONG_ARG = "--target-platforms";
  public static final String EXCLUDE_INCOMPATIBLE_TARGETS_LONG_ARG =
      "--exclude-incompatible-targets";

  /**
   * Contains all options defined in this class. These options are considered global since they are
   * known to all commands that inherit from this class.
   *
   * <p>The main purpose of having this list is to provide more structured help.
   */
  private static final ImmutableSet<String> GLOBAL_OPTIONS =
      ImmutableSet.of(
          HELP_LONG_ARG,
          NO_CACHE_LONG_ARG,
          OUTPUT_TEST_EVENTS_TO_FILE_LONG_ARG,
          PROFILE_PARSER_LONG_ARG,
          NUM_THREADS_LONG_ARG,
          CONFIG_LONG_ARG,
          CONFIG_FILE_LONG_ARG,
          VERBOSE_LONG_ARG,
          SKYLARK_PROFILE_LONG_ARG,
          TARGET_PLATFORMS_LONG_ARG,
          EXCLUDE_INCOMPATIBLE_TARGETS_LONG_ARG);

  public static boolean isGlobalOption(OptionHandler<?> optionHandler) {
    OptionDef option = optionHandler.option;
    if (option instanceof NamedOptionDef) {
      NamedOptionDef namedOption = (NamedOptionDef) option;
      return GLOBAL_OPTIONS.contains(namedOption.name());
    }
    return false;
  }

  public static boolean isGlobalOption(String name) {
    return GLOBAL_OPTIONS.contains(name);
  }
}
