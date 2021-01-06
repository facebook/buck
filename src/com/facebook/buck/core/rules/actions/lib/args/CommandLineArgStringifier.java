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

package com.facebook.buck.core.rules.actions.lib.args;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.artifact.ArtifactFilesystem;
import com.facebook.buck.core.artifact.OutputArtifact;
import com.google.devtools.build.lib.actions.CommandLineItem;

/** Helper methods to convert / validate objects that are command line arguments for actions */
public class CommandLineArgStringifier {
  private CommandLineArgStringifier() {}

  /**
   * @param filesystem the filesystem to use to stringify {@link Artifact}s
   * @param absolute If the path returned should be absolute. This can be necessary for functions
   *     like {@link ProcessBuilder#start()}. On windows it does not do path resolution properly for
   *     relative paths, even if the {@code directory} is set so an absolute path must be provided.
   * @param argAndFormatString the object to stringify and the format string to apply after initial
   *     stringification
   * @return the string representation of an argument to pass to a command line application in an
   *     action
   */
  public static String asString(
      ArtifactFilesystem filesystem,
      boolean absolute,
      CommandLineArgs.ArgAndFormatString argAndFormatString) {
    String stringValue = asString(filesystem, absolute, argAndFormatString.getObject());
    String formatString = argAndFormatString.getPostStringificationFormatString();
    if (formatString.isEmpty() || formatString.equals("%s")) {
      return stringValue;
    } else {
      return formatString.replace("%s", stringValue);
    }
  }

  /**
   * @param filesystem the filesystem to use to stringify {@link Artifact}s
   * @param absolute If the path returned should be absolute. This can be necessary for functions
   *     like {@link ProcessBuilder#start()}. On windows it does not do path resolution properly for
   *     relative paths, even if the {@code directory} is set so an absolute path must be provided.
   * @param object the object to stringify
   * @return the string representation of an argument to pass to a command line application in an
   *     action
   */
  public static String asString(ArtifactFilesystem filesystem, boolean absolute, Object object)
      throws CommandLineArgException {
    if (object instanceof String) {
      return (String) object;
    } else if (object instanceof Integer) {
      return object.toString();
    } else if (object instanceof CommandLineItem) {
      return CommandLineItem.expandToCommandLine(object);
    } else if (object instanceof Artifact) {
      return absolute
          ? filesystem.stringifyAbsolute((Artifact) object)
          : filesystem.stringify((Artifact) object);
    } else if (object instanceof OutputArtifact) {
      return absolute
          ? filesystem.stringifyAbsolute(((OutputArtifact) object).getArtifact())
          : filesystem.stringify(((OutputArtifact) object).getArtifact());
    } else {
      throw new CommandLineArgException(object);
    }
  }
}
