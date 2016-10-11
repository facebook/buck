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

import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;

import org.immutables.value.Value;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class AuditConfigCommand extends AbstractCommand {

  @Option(name = "--json",
      usage = "Output in JSON format")
  private boolean generateJsonOutput;

  public boolean shouldGenerateJsonOutput() {
    return generateJsonOutput;
  }

  @Option(name = "--tab", usage = "Output in a tab-delmiited format key/value format")
  private boolean generateTabbedOutput;

  public boolean shouldGenerateTabbedOutput() {
    return generateTabbedOutput;
  }

  @Argument
  private List<String> arguments = Lists.newArrayList();

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

  @Override
  public int runWithoutHelp(final CommandRunnerParams params)
      throws IOException, InterruptedException {
    if (shouldGenerateTabbedOutput() && shouldGenerateJsonOutput()) {
      params.getBuckEventBus().post(
          ConsoleEvent.severe(
              "--json and --tab cannot both be specified"));
      return 1;
    }

    final BuckConfig buckConfig = params.getBuckConfig();

    final ImmutableList<ConfigValue> configs = FluentIterable.from(
        getArguments()).transform(
        new Function<String, ConfigValue>() {
          @Override
          public ConfigValue apply(String input) {
            String[] parts = input.split("\\.", 2);
            if (parts.length != 2) {
              params.getConsole().getStdErr().println(
                  String.format("%s is not a valid section/property string", input));
              return ConfigValue.of(input, "", input, Optional.absent());
            }
            return ConfigValue.of(
                input,
                parts[0],
                parts[1],
                buckConfig.getValue(parts[0], parts[1]));
          }
        }).toList();

    if (shouldGenerateJsonOutput()) {
      printJsonOutput(params, configs);
    } else if (shouldGenerateTabbedOutput()) {
      printTabbedOutput(params, configs);
    } else {
      printBuckconfigOutput(params, configs);
    }
    return 0;
  }

  private void printTabbedOutput(
      final CommandRunnerParams params,
      ImmutableList<ConfigValue> configs) {
    for (ConfigValue config : configs) {
      params.getConsole().getStdOut().println(
          String.format(
              "%s\t%s",
              config.getKey(),
              config.getValue().or("")));
    }
  }

  private void printJsonOutput(
      final CommandRunnerParams params,
      ImmutableList<ConfigValue> configs) throws IOException {
    ImmutableMap.Builder<String, Optional<String>> jsBuilder;
    jsBuilder = ImmutableMap.builder();

    for (ConfigValue config : configs) {
      jsBuilder.put(config.getKey(), config.getValue());
    }

    params.getObjectMapper().writeValue(params.getConsole().getStdOut(), jsBuilder.build());
  }

  private void printBuckconfigOutput(
      final CommandRunnerParams params,
      ImmutableList<ConfigValue> configs) {
    ImmutableListMultimap<String, ConfigValue> iniData =
        FluentIterable.from(configs)
            .filter(
                new Predicate<ConfigValue>() {
                  @Override
                  public boolean apply(ConfigValue config) {
                    return config.getSection() != "" && config.getValue().isPresent();
                  }
                })
            .index(
                new Function<ConfigValue, String>() {
                  @Override
                  public String apply(ConfigValue config) {
                    return config.getSection();
                  }
                });

    for (Map.Entry<String, Collection<ConfigValue>> entry : iniData.asMap().entrySet()) {
      params.getConsole().getStdOut().println(String.format("[%s]", entry.getKey()));
      for (ConfigValue config : entry.getValue()) {
        params.getConsole().getStdOut().println(
            String.format(
                "    %s = %s",
                config.getProperty(),
                config.getValue().get()));
      }
      params.getConsole().getStdOut().println("");
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
