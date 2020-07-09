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

package com.facebook.buck.external;

import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.model.BuildableCommand;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Parser for args passed to StepsExecutable.
 *
 * <p>Two args are expected:
 *
 * <ol>
 *   <li>Buildable class name for the steps to be executed
 *   <li>Path to a file containing a {@link BuildableCommand}
 * </ol>
 */
class ExternalArgsParser {
  private static final int NUM_EXPECTED_ARGS = 2;

  /** Returns the {@link ParsedArgs} from the args passed directly to StepsExecutable. */
  @SuppressWarnings("unchecked")
  ParsedArgs parse(String[] args, ImmutableSet<Class<? extends Buildable>> validClasses) {
    Preconditions.checkNotNull(args, "Expected %s args. Received null args", NUM_EXPECTED_ARGS);
    Preconditions.checkArgument(
        args.length == NUM_EXPECTED_ARGS,
        "Expected %s args. Received %s",
        NUM_EXPECTED_ARGS,
        args.length);
    Class<? extends Buildable> buildableClass;
    try {
      buildableClass = (Class<? extends Buildable>) Class.forName(args[0]);
      Preconditions.checkArgument(
          validClasses.contains(buildableClass), "Invalid buildable class: %s", args[0]);
    } catch (ClassNotFoundException e) {
      throw new IllegalArgumentException(
          String.format("Cannot find buildable class: %s", args[0]), e);
    }
    try (InputStream inputStream = new FileInputStream(args[1])) {
      BuildableCommand buildableCommand = BuildableCommand.parseFrom(inputStream);
      return ParsedArgs.of(buildableClass, buildableCommand);
    } catch (IOException e) {
      throw new IllegalArgumentException("Cannot read buildable command", e);
    }
  }

  /** Data class representing args passed to StepsExecutable. */
  @BuckStyleValue
  abstract static class ParsedArgs {
    abstract Class<? extends Buildable> getBuildableClass();

    abstract BuildableCommand getBuildableCommand();

    static ParsedArgs of(
        Class<? extends Buildable> buildableName, BuildableCommand buildableCommand) {
      return ImmutableParsedArgs.ofImpl(buildableName, buildableCommand);
    }
  }
}
