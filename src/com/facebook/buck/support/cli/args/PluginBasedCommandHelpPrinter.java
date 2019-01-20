/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.support.cli.args;

import com.google.common.base.Functions;
import com.google.common.collect.ImmutableSortedMap;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import org.kohsuke.args4j.OptionHandlerFilter;

/** A helper class that encapsulate printing of usage tests for a given command */
public class PluginBasedCommandHelpPrinter {

  /**
   * Prints the text with usage information for the given command to the provided stream.
   *
   * <p>This method divides command options into multiple categories: global options (common to all
   * commands), common options for this command and options for every plugin-based subcommand.
   */
  public void printUsage(PluginBasedCommand command, PrintStream stream) {
    printShortDescription(command, stream);
    CmdLineParserWithPrintInformation parser = new CmdLineParserWithPrintInformation(command);
    ImmutableSortedMap<PluginBasedSubCommand, CmdLineParserWithPrintInformation> subCommands =
        command
            .getSubCommands()
            .stream()
            .collect(
                ImmutableSortedMap.toImmutableSortedMap(
                    Comparator.comparing(PluginBasedSubCommand::getOptionValue),
                    Functions.identity(),
                    CmdLineParserWithPrintInformation::new));

    int len = calculateTotalMaxLen(parser, subCommands.values());

    OutputStreamWriter writer = new OutputStreamWriter(stream);

    printGlobalOptionsUsage(stream, writer, parser, len);
    printCommonOptionsUsage(stream, writer, parser, len);

    for (Map.Entry<PluginBasedSubCommand, CmdLineParserWithPrintInformation> subCommand :
        subCommands.entrySet()) {
      printSubCommandUsage(
          stream,
          writer,
          subCommand.getKey(),
          subCommand.getValue(),
          command.getTypeOptionName(),
          len);
    }

    stream.println();
  }

  private static void printShortDescription(PluginBasedCommand command, PrintStream stream) {
    stream.println("Description: ");
    stream.println("  " + command.getShortDescription());
    stream.println();
  }

  private int calculateTotalMaxLen(
      CmdLineParserWithPrintInformation parser,
      Collection<CmdLineParserWithPrintInformation> subCommandParsers) {
    int maxLen = parser.calculateMaxLen();
    for (CmdLineParserWithPrintInformation subCommandParser : subCommandParsers) {
      maxLen = Math.max(maxLen, subCommandParser.calculateMaxLen());
    }
    return maxLen;
  }

  private void printGlobalOptionsUsage(
      PrintStream stream,
      OutputStreamWriter writer,
      CmdLineParserWithPrintInformation parser,
      int len) {
    stream.println("Global options:");
    parser.printUsage(writer, GlobalCliOptions::isGlobalOption, len);
    stream.println();
  }

  private void printCommonOptionsUsage(
      PrintStream stream,
      OutputStreamWriter writer,
      CmdLineParserWithPrintInformation parser,
      int len) {
    stream.println("Common options:");
    parser.printUsage(
        writer, optionHandler -> !GlobalCliOptions.isGlobalOption(optionHandler), len);
    stream.println();
  }

  private void printSubCommandUsage(
      PrintStream stream,
      OutputStreamWriter writer,
      PluginBasedSubCommand cmd,
      CmdLineParserWithPrintInformation subCommandParser,
      String typeOptionName,
      int len) {
    String message =
        cmd.getShortDescription() != null ? " (" + cmd.getShortDescription() + ")" : "";
    stream.println("Options for " + typeOptionName + " " + cmd.getOptionValue() + message + ":");
    subCommandParser.printUsage(writer, OptionHandlerFilter.PUBLIC, len);
    stream.println();
  }
}
