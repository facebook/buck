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

package com.facebook.buck.external.parser;

import com.facebook.buck.external.model.ExternalAction;
import com.facebook.buck.external.model.ParsedArgs;
import com.facebook.buck.rules.modern.model.BuildableCommand;
import com.google.common.base.Preconditions;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Parser for args passed to external actions main class.
 *
 * <p>Two args are expected:
 *
 * <ol>
 *   <li>External action class name for the steps to be executed
 *   <li>Path to a file containing a {@link BuildableCommand}
 * </ol>
 */
public class ExternalArgsParser {
  private static final int NUM_EXPECTED_ARGS = 1;

  /**
   * Returns the {@link ParsedArgs} from the args passed directly to external actions main class.
   */
  @SuppressWarnings("unchecked")
  public ParsedArgs parse(String[] args) {
    Preconditions.checkNotNull(args, "Expected %s arg. Received null args", NUM_EXPECTED_ARGS);
    Preconditions.checkArgument(
        args.length == NUM_EXPECTED_ARGS,
        "Expected %s arg. Received %s",
        NUM_EXPECTED_ARGS,
        args.length);
    try (InputStream inputStream = new FileInputStream(args[0])) {
      BuildableCommand buildableCommand = BuildableCommand.parseFrom(inputStream);
      String externalActionClassName = buildableCommand.getExternalActionClass();
      try {
        Class<? extends ExternalAction> externalAction =
            (Class<? extends ExternalAction>) Class.forName(externalActionClassName);
        return ParsedArgs.of(externalAction, buildableCommand);
      } catch (ClassNotFoundException e) {
        throw new IllegalArgumentException(
            String.format("Cannot find external actions class: %s", externalActionClassName), e);
      }
    } catch (IOException e) {
      throw new IllegalArgumentException("Cannot read buildable command", e);
    }
  }
}
