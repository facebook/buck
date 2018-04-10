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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.PatternAndMessage;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class UnexpectedFlavorException extends HumanReadableException {

  private static final ImmutableSet<PatternAndMessage> suggestedMessagesForFlavors =
      ImmutableSet.of(
          PatternAndMessage.of(
              Pattern.compile("android-*"),
              "Please make sure you have the Android SDK/NDK installed and set up. See "
                  + "https://buckbuild.com/setup/install.html#locate-android-sdk"),
          PatternAndMessage.of(
              Pattern.compile("macosx*"),
              "Please make sure you have the Mac OSX SDK installed and set up."),
          PatternAndMessage.of(
              Pattern.compile("iphoneos*"),
              "Please make sure you have the iPhone SDK installed and set up."),
          PatternAndMessage.of(
              Pattern.compile("iphonesimulator*"),
              "Please make sure you have the iPhone Simulator installed and set up."),
          PatternAndMessage.of(
              Pattern.compile("watchos*"),
              "Please make sure you have the Apple Watch SDK installed and set up."),
          PatternAndMessage.of(
              Pattern.compile("watchsimulator*"),
              "Please make sure you have the Watch Simulator installed and set up."),
          PatternAndMessage.of(
              Pattern.compile("appletvos*"),
              "Please make sure you have the Apple TV SDK installed and set up."),
          PatternAndMessage.of(
              Pattern.compile("appletvsimulator*"),
              "Plase make sure you have the Apple TV Simulator installed and set up."));

  private UnexpectedFlavorException(String message) {
    super(message);
  }

  public static UnexpectedFlavorException createWithSuggestions(
      Flavored flavored, Cell cell, BuildTarget target) {
    ImmutableSet<Flavor> invalidFlavors = getInvalidFlavors(flavored, target);
    ImmutableSet<Flavor> validFlavors = getValidFlavors(flavored, target);
    // Get the specific message
    String exceptionMessage = createDefaultMessage(target, invalidFlavors, validFlavors);
    // Get some suggestions on how to solve it.
    Optional<ImmutableSet<PatternAndMessage>> configMessagesForFlavors =
        cell.getBuckConfig().getUnexpectedFlavorsMessages();

    ImmutableList.Builder<String> suggestionsBuilder = ImmutableList.builder();
    for (Flavor flavor : invalidFlavors) {
      boolean foundInConfig = false;
      if (configMessagesForFlavors.isPresent()) {
        for (PatternAndMessage flavorPattern : configMessagesForFlavors.get()) {
          if (flavorPattern.getPattern().matcher(flavor.getName()).find()) {
            foundInConfig = true;
            suggestionsBuilder.add("- " + flavor.getName() + ": " + flavorPattern.getMessage());
          }
        }
      }
      if (!foundInConfig) {
        for (PatternAndMessage flavorPattern : suggestedMessagesForFlavors) {
          if (flavorPattern.getPattern().matcher(flavor.getName()).find()) {
            suggestionsBuilder.add("- " + flavor.getName() + ": " + flavorPattern.getMessage());
          }
        }
      }
    }
    ImmutableList<String> suggestions = suggestionsBuilder.build();

    exceptionMessage +=
        String.format(
            "\n\n"
                + "- Please check the spelling of the flavor(s).\n"
                + "- If the spelling is correct, please check that the related SDK has been installed.");
    if (!suggestions.isEmpty()) {
      exceptionMessage += "\n" + String.join(", ", suggestions);
    }

    return new UnexpectedFlavorException(exceptionMessage);
  }

  private static ImmutableSet<Flavor> getInvalidFlavors(Flavored flavored, BuildTarget target) {
    return target
        .getFlavors()
        .stream()
        .filter(flavor -> !flavored.hasFlavors(ImmutableSet.of(flavor)))
        .collect(ImmutableSet.toImmutableSet());
  }

  private static ImmutableSet<Flavor> getValidFlavors(Flavored flavored, BuildTarget target) {
    return target
        .getFlavors()
        .stream()
        .filter(flavor -> flavored.hasFlavors(ImmutableSet.of(flavor)))
        .collect(ImmutableSet.toImmutableSet());
  }

  private static String createDefaultMessage(
      BuildTarget target, ImmutableSet<Flavor> invalidFlavors, ImmutableSet<Flavor> validFlavors) {
    String invalidFlavorsStr =
        invalidFlavors
            .stream()
            .map(Flavor::toString)
            .collect(Collectors.joining(System.lineSeparator()));

    String validFlavorsStr =
        validFlavors
            .stream()
            .map(Flavor::getName)
            .collect(Collectors.joining(System.lineSeparator()));

    String invalidFlavorsDisplayStr = String.join(", ", invalidFlavorsStr);

    return String.format(
        "The following flavor(s) are not supported on target %s:\n%s\n\nAvailable flavors are:\n%s\n",
        target, invalidFlavorsDisplayStr, validFlavorsStr);
  }
}
