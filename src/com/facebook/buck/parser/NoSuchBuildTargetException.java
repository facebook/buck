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

package com.facebook.buck.parser;

import static com.facebook.buck.util.BuckConstant.BUILD_RULES_FILE_NAME;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;

import java.nio.file.Path;

@SuppressWarnings("serial")
public class NoSuchBuildTargetException extends BuildTargetException {

  private NoSuchBuildTargetException(String message) {
    super(message);
  }

  /**
   * @param directoryPath missing directory
   * @param buildTargetName missing build target name
   * @param parseContext the context used when attempting to resolve the build target
   */
  static NoSuchBuildTargetException createForMissingDirectory(Path directoryPath,
      String buildTargetName,
      ParseContext parseContext) {
    String message = String.format("No directory %s when resolving target %s",
        directoryPath,
        makeTargetDescription(buildTargetName, parseContext));
    return new NoSuchBuildTargetException(message);
  }

  /**
   * @param buildFilePath missing build file
   * @param buildTargetName missing build target name
   * @param parseContext the context used when attempting to resolve the build target
   */
  static NoSuchBuildTargetException createForMissingBuildFile(Path buildFilePath,
      String buildTargetName,
      ParseContext parseContext) {
    String message = String.format("No %s file %s when resolving target %s",
        BUILD_RULES_FILE_NAME,
        buildFilePath,
        makeTargetDescription(buildTargetName, parseContext));
    return new NoSuchBuildTargetException(message);
  }

  /**
   * @param buildTarget the failing {@link com.facebook.buck.model.BuildTarget}
   */
  static NoSuchBuildTargetException createForMissingBuildRule(BuildTarget buildTarget,
      ParseContext parseContext) {
    String message = String.format("No rule found when resolving target %s",
        makeTargetDescription(buildTarget.getFullyQualifiedName(), parseContext));

    return new NoSuchBuildTargetException(message);
  }

  @Override
  public String getHumanReadableErrorMessage() {
    return getMessage();
  }

  /**
   * @return description of the target name and context being parsed when an error was encountered.
   *     Examples are ":azzetz in build file //first-party/orca/orcaapp/BUCK" and
   *     "//first-party/orca/orcaapp:mezzenger in context FULLY_QUALIFIED"
   */
  private static String makeTargetDescription(String buildTargetName, ParseContext parseContext) {
    String location = parseContext.getType().name();
    if (parseContext.getType() == ParseContext.Type.BUILD_FILE) {
      return String.format("%s in build file %s%s",
          buildTargetName,
          parseContext.getBaseNameWithSlash(),
          BUILD_RULES_FILE_NAME);
    } else {
      return String.format("%s in context %s", buildTargetName, location);
    }
  }
}
