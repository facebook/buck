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

package com.facebook.buck.intellij.ideabuck.ui.utils;

import com.google.common.collect.ImmutableList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ErrorExtractor {

  private static final Pattern MAIN_PATTERN =
      Pattern.compile("(.*):(\\-?\\d+):\\s(error|warning):", Pattern.MULTILINE);
  private static final Pattern EOM_PATTERN = Pattern.compile("Errors: \\d+\\.", Pattern.MULTILINE);
  private ImmutableList<CompilerErrorItem> mErrors;

  public ErrorExtractor(String errorMessage) {
    Matcher matcher = MAIN_PATTERN.matcher(errorMessage);
    ImmutableList.Builder<CompilerErrorItem> builder =
        new ImmutableList.Builder<CompilerErrorItem>();

    CompilerErrorItem currentError = null;
    int lastMatchEnd = -1;

    while (matcher.find()) {
      if (currentError != null) {
        // add the last one
        String currentErrorMessage = errorMessage.substring(lastMatchEnd, matcher.start()).trim();
        currentError.setError(currentErrorMessage);
        currentError.setColumn(findColumn(currentErrorMessage));
        builder.add(currentError);
      }
      currentError =
          new CompilerErrorItem(
              matcher.group(1),
              Integer.parseInt(matcher.group(2)),
              matcher.group(3).equalsIgnoreCase("error")
                  ? CompilerErrorItem.Type.ERROR
                  : CompilerErrorItem.Type.WARNING);
      lastMatchEnd = matcher.end();
    }
    if (currentError != null) {
      Matcher matchEnd = EOM_PATTERN.matcher(errorMessage);
      String currentErrorMessage = "";

      if (matchEnd.find(lastMatchEnd)) {
        // Add the rest
        currentErrorMessage = errorMessage.substring(lastMatchEnd, matchEnd.start()).trim();
      } else {
        // Not found the end, add everything as an error message.
        currentErrorMessage = errorMessage.substring(lastMatchEnd).trim();
      }
      currentError.setError(currentErrorMessage);
      currentError.setColumn(findColumn(currentErrorMessage));
      builder.add(currentError);
    }
    mErrors = builder.build();
  }

  public ImmutableList<CompilerErrorItem> getErrors() {
    return mErrors;
  }

  private int findColumn(String currentErrorMessage) {
    String[] lines = currentErrorMessage.split("\n");
    for (String currentLine : lines) {
      if (currentLine.endsWith("^")) {
        return currentLine.length();
      }
    }
    return -1;
  }
}
