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

package com.facebook.buck.apple.simulator;

import com.facebook.buck.log.Logger;

import com.google.common.collect.ImmutableSet;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class to parse the output of `xcrun simctl list`.
 */
public class SimctlListOutputParsing {
  private static final Logger LOG = Logger.get(SimctlListOutputParsing.class);

  private static final String DEVICE_NAME_GROUP = "name";
  private static final String DEVICE_UDID_GROUP = "udid";
  private static final String DEVICE_STATE_GROUP = "state";
  private static final String DEVICE_UNAVAILABLE_GROUP = "unavailable";

  private static final Pattern SIMCTL_LIST_DEVICES_PATTERN = Pattern.compile(
      " *(?<" + DEVICE_NAME_GROUP + ">.+) " +
      "\\((?<" + DEVICE_UDID_GROUP + ">[0-9A-F-]+)\\) " +
      "\\((?<" + DEVICE_STATE_GROUP + ">Creating|Booting|Shutting Down|Shutdown|Booted)\\)" +
      "(?<" + DEVICE_UNAVAILABLE_GROUP + ">\\(unavailable, .*\\))?");

  // Utility class; do not instantiate.
  private SimctlListOutputParsing() { }

  /**
   * Parses each line of input from {@code reader}, adding any available simulators to
   * {@code simulatorsBuilder}.
   */
  public static void parseOutputFromReader(
      Reader reader,
      ImmutableSet.Builder<AppleSimulator> simulatorsBuilder) throws IOException {
    try (BufferedReader br = new BufferedReader(reader)) {
      String line;
      while ((line = br.readLine()) != null) {
        parseLine(line, simulatorsBuilder);
      }
    }
  }

  private static void parseLine(
      String line,
      ImmutableSet.Builder<AppleSimulator> simulatorsBuilder) {
    LOG.debug("Parsing simctl list output line: %s", line);
    Matcher matcher = SIMCTL_LIST_DEVICES_PATTERN.matcher(line);
    if (matcher.matches() && matcher.group(DEVICE_UNAVAILABLE_GROUP) == null) {
      AppleSimulator simulator =
          AppleSimulator.builder()
              .setName(matcher.group(DEVICE_NAME_GROUP))
              .setUdid(matcher.group(DEVICE_UDID_GROUP))
              .setSimulatorState(AppleSimulatorState.fromString(matcher.group(DEVICE_STATE_GROUP)))
              .build();
      LOG.debug("Got simulator: %s", simulator);
      simulatorsBuilder.add(simulator);
    }
  }
}
