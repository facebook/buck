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

package com.facebook.buck.cli;

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.util.BuckCellArg;
import com.facebook.buck.util.DirtyPrintStreamDecorator;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.json.ObjectMappers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.immutables.value.Value;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

public class AuditConfigCommand extends AbstractCommand {
  @Option(
      name = "--json",
      usage = "Output in JSON format",
      forbids = {"--tab"})
  private boolean generateJsonOutput;

  public boolean shouldGenerateJsonOutput() {
    return generateJsonOutput;
  }

  @Option(
      name = "--tab",
      usage = "Output in a tab-delmiited format key/value format",
      forbids = {"--json"})
  private boolean generateTabbedOutput;

  public boolean shouldGenerateTabbedOutput() {
    return generateTabbedOutput;
  }

  @Argument private List<String> arguments = new ArrayList<>();

  public List<String> getArguments() {
    return arguments;
  }

  @BuckStyleImmutable
  @Value.Immutable
  abstract static class AbstractConfigValue {
    @Value.Parameter
    public abstract String getKey();

    @Value.Parameter
    public abstract String getSection();

    @Value.Parameter
    public abstract String getProperty();

    @Value.Parameter
    public abstract Optional<String> getValue();
  }

  private ImmutableSortedSet<ConfigValue> readConfig(CommandRunnerParams params) {
    Cell rootCell = params.getCell();

    ImmutableSortedSet.Builder<ConfigValue> builder;
    builder =
        ImmutableSortedSet.orderedBy(
            Comparator.comparing(ConfigValue::getSection).thenComparing(ConfigValue::getKey));
    if (getArguments().isEmpty()) {
      // Dump entire config.
      BuckConfig buckConfig = rootCell.getBuckConfig();
      buckConfig
          .getConfig()
          .getSectionToEntries()
          .forEach(
              (section_name, section) ->
                  section.forEach(
                      (key, val) ->
                          builder.add(ConfigValue.of(key, section_name, key, Optional.of(val)))));
    } else {
      // Dump specified sections/values
      getArguments()
          .forEach(
              input -> {
                BuckCellArg arg = BuckCellArg.of(input);
                BuckConfig buckConfig = getCellBuckConfig(rootCell, arg.getCellName());
                String[] parts = arg.getArg().split("\\.", 2);

                DirtyPrintStreamDecorator stdErr = params.getConsole().getStdErr();

                if (parts.length == 0 || parts.length > 2) {
                  stdErr.println(String.format("%s is not a valid section/property string", input));
                  return;
                }

                Optional<ImmutableMap<String, String>> section = buckConfig.getSection(parts[0]);
                if (!section.isPresent()) {
                  stdErr.println(
                      String.format(
                          "%s is not a valid section string, when processing arg %s",
                          parts[0], input));
                  return;
                }

                if (parts.length == 1) {
                  // Dump entire section.
                  section
                      .get()
                      .forEach(
                          (key, val) ->
                              builder.add(
                                  ConfigValue.of(
                                      String.join(".", input, key), input, key, Optional.of(val))));
                } else {
                  // Dump specified value
                  builder.add(
                      ConfigValue.of(
                          input,
                          parts[0],
                          parts[1],
                          Optional.ofNullable(section.get().get(parts[1]))));
                }
              });
    }
    return builder.build();
  }

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params)
      throws IOException, InterruptedException {
    ImmutableSortedSet<ConfigValue> configs = readConfig(params);

    if (shouldGenerateJsonOutput()) {
      printJsonOutput(params, configs);
    } else if (shouldGenerateTabbedOutput()) {
      printTabbedOutput(params, configs);
    } else {
      printBuckconfigOutput(params, configs);
    }
    return ExitCode.SUCCESS;
  }

  private BuckConfig getCellBuckConfig(Cell cell, Optional<String> cellName) {
    Optional<Path> cellPath = cell.getCellPathResolver().getCellPath(cellName);
    if (!cellPath.isPresent()) {
      return cell.getBuckConfig();
    }
    return cell.getCell(cellPath.get()).getBuckConfig();
  }

  private void printTabbedOutput(
      CommandRunnerParams params, ImmutableSortedSet<ConfigValue> configs) {
    for (ConfigValue config : configs) {
      params
          .getConsole()
          .getStdOut()
          .println(String.format("%s\t%s", config.getKey(), config.getValue().orElse("")));
    }
  }

  private void printJsonOutput(CommandRunnerParams params, ImmutableSortedSet<ConfigValue> configs)
      throws IOException {
    ImmutableMap.Builder<String, Optional<String>> jsBuilder;
    jsBuilder = ImmutableMap.builder();

    for (ConfigValue config : configs) {
      jsBuilder.put(config.getKey(), config.getValue());
    }

    ObjectMappers.WRITER.writeValue(params.getConsole().getStdOut(), jsBuilder.build());
  }

  private void printBuckconfigOutput(
      CommandRunnerParams params, ImmutableSortedSet<ConfigValue> configs) {
    ImmutableListMultimap<String, ConfigValue> iniData =
        FluentIterable.from(configs)
            .filter(config -> !config.getSection().isEmpty() && config.getValue().isPresent())
            .index(ConfigValue::getSection);

    for (Map.Entry<String, Collection<ConfigValue>> entry : iniData.asMap().entrySet()) {
      params.getConsole().getStdOut().println(String.format("[%s]", entry.getKey()));
      for (ConfigValue config : entry.getValue()) {
        params
            .getConsole()
            .getStdOut()
            .println(String.format("    %s = %s", config.getProperty(), config.getValue().get()));
      }
      params.getConsole().getStdOut().println();
    }
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }

  @Override
  public String getShortDescription() {
    return "provides facilities to audit configuration values";
  }
}
