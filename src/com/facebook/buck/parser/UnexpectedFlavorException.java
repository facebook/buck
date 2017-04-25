/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.parser;

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.UnflavoredBuildTarget;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.PatternAndMessage;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;
import java.util.regex.Pattern;

public class UnexpectedFlavorException extends HumanReadableException {

  private static final ImmutableSet<PatternAndMessage> suggestedMessagesForFlavors =
      ImmutableSet.of(
          PatternAndMessage.of(
              Pattern.compile("android-*"),
              "Make sure you have the Android SDK/NDK installed and set up. See "
                  + "https://buckbuild.com/setup/install.html#locate-android-sdk"),
          PatternAndMessage.of(
              Pattern.compile("macosx*"),
              "Make sure you have the Mac OSX SDK installed and set up."),
          PatternAndMessage.of(
              Pattern.compile("iphoneos*"),
              "Make sure you have the iPhone SDK installed and set up."),
          PatternAndMessage.of(
              Pattern.compile("iphonesimulator*"),
              "Make sure you have the iPhone Simulator installed and set up."),
          PatternAndMessage.of(
              Pattern.compile("watchos*"),
              "Make sure you have the Apple Watch SDK installed and set up."),
          PatternAndMessage.of(
              Pattern.compile("watchsimulator*"),
              "Make sure you have the Watch Simulator installed and set up."),
          PatternAndMessage.of(
              Pattern.compile("appletvos*"),
              "Make sure you have the Apple TV SDK installed and set up."),
          PatternAndMessage.of(
              Pattern.compile("appletvsimulator*"),
              "Make sure you have the Apple TV Simulator installed and set up."));

  private UnexpectedFlavorException(String message) {
    super(message);
  }

  public static UnexpectedFlavorException createWithSuggestions(Cell cell, BuildTarget target) {
    // Get the specific message
    String exceptionMessage = createDefaultMessage(cell, target);
    // Get some suggestions on how to solve it.
    String suggestions = "";
    Optional<ImmutableSet<PatternAndMessage>> configMessagesForFlavors =
        cell.getBuckConfig().getUnexpectedFlavorsMessages();

    for (Flavor flavor : target.getFlavors()) {
      boolean foundInConfig = false;
      if (configMessagesForFlavors.isPresent()) {
        for (PatternAndMessage flavorPattern : configMessagesForFlavors.get()) {
          if (flavorPattern.getPattern().matcher(flavor.getName()).find()) {
            foundInConfig = true;
            suggestions += flavor.getName() + " : " + flavorPattern.getMessage() + "\n";
          }
        }
      }
      if (!foundInConfig) {
        for (PatternAndMessage flavorPattern : suggestedMessagesForFlavors) {
          if (flavorPattern.getPattern().matcher(flavor.getName()).find()) {
            suggestions += flavor.getName() + " : " + flavorPattern.getMessage() + "\n";
          }
        }
      }
    }

    if (!suggestions.isEmpty()) {
      exceptionMessage +=
          "\nHere are some things you can try to get the following "
              + "flavors to work::\n"
              + suggestions;
    }

    return new UnexpectedFlavorException(exceptionMessage);
  }

  private static String createDefaultMessage(Cell cell, BuildTarget target) {
    return "Unrecognized flavor in target "
        + target
        + " while parsing "
        + UnflavoredBuildTarget.BUILD_TARGET_PREFIX
        + MorePaths.pathWithUnixSeparators(target.getBasePath().resolve(cell.getBuildFileName()));
  }
}
