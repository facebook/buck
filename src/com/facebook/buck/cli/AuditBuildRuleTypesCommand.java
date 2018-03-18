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

import com.facebook.buck.rules.KnownBuildRuleTypes;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.json.ObjectMappers;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringWriter;
import java.util.Collection;
import org.kohsuke.args4j.Option;

public class AuditBuildRuleTypesCommand extends AbstractCommand {

  @Option(name = "--json", usage = "Output in JSON format")
  private boolean generateJsonOutput;

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params)
      throws IOException, InterruptedException {
    collectAndDumpBuildRuleTypesInformation(
        params.getConsole(),
        params.getKnownBuildRuleTypesProvider().get(params.getCell()),
        generateJsonOutput);
    return ExitCode.SUCCESS;
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }

  @Override
  public String getShortDescription() {
    return "List all known build rule types";
  }

  static void collectAndDumpBuildRuleTypesInformation(
      Console console, KnownBuildRuleTypes knownBuildRuleTypes, boolean generateJsonOutput)
      throws IOException {
    Collection<String> buildRuleTypes = collectBuildRuleTypes(knownBuildRuleTypes);

    if (generateJsonOutput) {
      dumpBuildRuleTypesInJsonFormat(console, buildRuleTypes);
    } else {
      dumpBuildRuleTypesInRawFormat(console, buildRuleTypes);
    }
  }

  private static Collection<String> collectBuildRuleTypes(KnownBuildRuleTypes knownBuildRuleTypes) {
    return ImmutableSortedSet.copyOf(knownBuildRuleTypes.getTypesByName().keySet());
  }

  private static void dumpBuildRuleTypesInJsonFormat(
      Console console, Collection<String> buildRuleTypes) throws IOException {
    StringWriter stringWriter = new StringWriter();
    ObjectMappers.WRITER.withDefaultPrettyPrinter().writeValue(stringWriter, buildRuleTypes);
    console.getStdOut().println(stringWriter.getBuffer().toString());
  }

  private static void dumpBuildRuleTypesInRawFormat(
      Console console, Collection<String> buildRuleTypes) {
    PrintStream printStream = console.getStdOut();
    buildRuleTypes.forEach(printStream::println);
  }
}
