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

package com.facebook.buck.rules.args;

import com.facebook.buck.core.artifact.Artifact;
import com.google.devtools.build.lib.actions.CommandLineItem;

/**
 * Simple factory class to convert objects from {@link
 * com.facebook.buck.core.rules.actions.lib.args.CommandLineArgs} to {@link Arg} objects
 */
public class ArgFactory {
  private ArgFactory() {}

  public static Arg from(Object object, String formatString) {
    return ImmutableFormatArg.of(from(object), formatString);
  }

  /**
   * Convert a command line argument to an {@link Arg}
   *
   * @param object the object ({@link String}, {@link Artifact}, etc) to convert to an {@link Arg}
   * @return the arg
   * @throws IllegalArgumentException if {@code object} is not able to be converted, or the provided
   *     object was an unbound {@link Artifact}
   */
  public static Arg from(Object object) {
    if (object instanceof String) {
      return StringArg.of((String) object);
    } else if (object instanceof Integer) {
      return StringArg.of(object.toString());
    } else if (object instanceof CommandLineItem) {
      return StringArg.of(CommandLineItem.expandToCommandLine(object));
    } else if (object instanceof Artifact) {
      Artifact artifact = (Artifact) object;
      if (!artifact.isBound()) {
        throw new IllegalArgumentException(String.format("Artifact %s was not bound", artifact));
      }
      return SourcePathArg.of(artifact.asBound().getSourcePath());
    } else {
      throw new IllegalArgumentException(
          String.format("Invalid command line argument %s provided", object));
    }
  }
}
