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

import com.facebook.buck.core.exceptions.HumanReadableException;

/** Thrown when trying to create a {@link CommandLineArgStringifier} from an invalid type */
public class CommandLineArgException extends HumanReadableException {

  /** Creates an instance of {@link CommandLineArgException} */
  public CommandLineArgException(Object arg) {
    super(
        "Invalid command line argument type for %s. Must be a string, integer, artifact, or label",
        arg);
  }

  public CommandLineArgException(String formatString, Object arg) {
    super(formatString, arg);
  }
}
