/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.cli;

import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.rules.keys.config.impl.BuckBinaryHashProvider;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.json.ObjectMappers;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.annotation.Nullable;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

/** A subcommand in `buck audit` that shows information about Buck build. */
public class AuditBuildInfoCommand extends AbstractCommand {

  @Option(name = "--json", usage = "Output in JSON format")
  private boolean generateJsonOutput;

  @Argument private List<String> arguments = new ArrayList<>();

  @Override
  public boolean isReadOnly() {
    return true;
  }

  @Override
  public String getShortDescription() {
    return "Print Buck build information like binary hash and Buck repository details";
  }

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params)
      throws IOException, InterruptedException {
    try {
      collectAndDumpBuildInformation(params.getConsole(), arguments, generateJsonOutput);
    } catch (IllegalArgumentException e) {
      params.getBuckEventBus().post(ConsoleEvent.severe(e.getMessage()));
      return ExitCode.FATAL_GENERIC;
    }
    return ExitCode.SUCCESS;
  }

  static void collectAndDumpBuildInformation(
      Console console, Collection<String> arguments, boolean generateJsonOutput) {
    ImmutableCollection<BuildInfoFields> requestedFields = getRequestedFields(arguments);

    ImmutableMap<String, String> informationFromRequestedFields =
        collectInformationFromFields(requestedFields);

    dumpFieldsInformation(console, informationFromRequestedFields, generateJsonOutput);
  }

  private static ImmutableCollection<BuildInfoFields> getRequestedFields(
      Collection<String> arguments) throws IllegalArgumentException {
    if (arguments.isEmpty()) {
      return ImmutableSet.copyOf(BuildInfoFields.values());
    } else {
      return collectFieldsFromArguments(arguments);
    }
  }

  private static ImmutableSet<BuildInfoFields> collectFieldsFromArguments(
      Collection<String> arguments) throws IllegalArgumentException {
    ImmutableSet.Builder<BuildInfoFields> fieldsFromArguments = ImmutableSet.builder();

    for (String argument : Sets.newHashSet(arguments)) {
      boolean found = false;
      for (BuildInfoFields field : BuildInfoFields.values()) {
        if (field.toString().equalsIgnoreCase(argument)) {
          fieldsFromArguments.add(field);
          found = true;
          break;
        }
      }
      if (!found) {
        throw new IllegalArgumentException(String.format("Unknown field: %s", argument));
      }
    }

    return fieldsFromArguments.build();
  }

  private static ImmutableMap<String, String> collectInformationFromFields(
      ImmutableCollection<BuildInfoFields> requestedFields) {
    ImmutableMap.Builder<String, String> collectedFields = ImmutableMap.builder();

    for (BuildInfoFields field : requestedFields) {
      collectedFields.put(field.toString().toLowerCase(), String.valueOf(field.getValue()));
    }

    return collectedFields.build();
  }

  private static void dumpFieldsInformation(
      Console console, ImmutableMap<String, String> fieldsInformation, boolean generateJsonOutput) {
    if (generateJsonOutput) {
      dumpFieldsInformationInJsonFormat(console, fieldsInformation);
    } else {
      dumpFieldsInformationInPlainFormat(console, fieldsInformation);
    }
  }

  private static void dumpFieldsInformationInPlainFormat(
      Console console, ImmutableMap<String, String> fieldsInformation) {
    ImmutableSortedMap.copyOf(fieldsInformation)
        .forEach((name, value) -> console.getStdOut().println(name + " = " + value));
  }

  private static void dumpFieldsInformationInJsonFormat(
      Console console, ImmutableMap<String, String> fieldsInformation) {
    StringWriter stringWriter = new StringWriter();
    try {
      ObjectMappers.WRITER.withDefaultPrettyPrinter().writeValue(stringWriter, fieldsInformation);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    console.getStdOut().println(stringWriter.getBuffer().toString());
  }

  private enum BuildInfoFields {
    BUCK_BINARY_HASH {
      @Override
      String getValue() {
        return BuckBinaryHashProvider.getBuckBinaryHash();
      }
    },
    BUCK_BUILD_COMMIT_ID {
      @Override
      @Nullable
      String getValue() {
        return System.getProperty("buck.git_commit");
      }
    },
    BUCK_BUILD_COMMIT_TIMESTAMP {
      @Override
      @Nullable
      String getValue() {
        return System.getProperty("buck.git_commit_timestamp");
      }
    },
    BUCK_BUILD_IS_DIRTY {
      @Override
      @Nullable
      String getValue() {
        return System.getProperty("buck.git_dirty");
      }
    };

    abstract @Nullable String getValue();
  }
}
