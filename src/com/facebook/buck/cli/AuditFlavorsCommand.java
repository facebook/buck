/*
 * Copyright 2013-present Facebook, Inc.
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
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.util.DirtyPrintStreamDecorator;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.MoreExceptions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * List flavor domains for build targets.
 */
public class AuditFlavorsCommand extends AbstractCommand {

  @Option(name = "--json",
      usage = "Output in JSON format")
  private boolean generateJsonOutput;

  public boolean shouldGenerateJsonOutput() {
    return generateJsonOutput;
  }

  @Override
  public String getShortDescription() {
    return "List flavor domains for build targets.";
  }

  @Argument
  private List<String> arguments = Lists.newArrayList();

  public List<String> getArguments() {
    return arguments;
  }

  private List<String> getArgumentsFormattedAsBuildTargets(BuckConfig buckConfig) {
    return getCommandLineBuildTargetNormalizer(buckConfig).normalizeAll(getArguments());
  }

  @Override
  public int runWithoutHelp(CommandRunnerParams params) throws IOException, InterruptedException {
    ImmutableSet<BuildTarget> targets =
        getArgumentsFormattedAsBuildTargets(params.getBuckConfig()).stream()
            .map(input -> BuildTargetParser.INSTANCE.parse(
                input,
                BuildTargetPatternParser.fullyQualified(),
                params.getCell().getCellPathResolver()))
            .collect(MoreCollectors.toImmutableSet());

    if (targets.isEmpty()) {
      params.getBuckEventBus().post(ConsoleEvent.severe(
          "Please specify at least one build target."));
      return 1;
    }

    ImmutableList.Builder<TargetNode<?, ?>> builder = ImmutableList.builder();
    try (CommandThreadManager pool = new CommandThreadManager(
        "Audit",
        getConcurrencyLimit(params.getBuckConfig()))) {
          for (BuildTarget target : targets) {
            TargetNode<?, ?> targetNode = params.getParser().getTargetNode(
                params.getBuckEventBus(),
                params.getCell(),
                getEnableParserProfiling(),
                pool.getExecutor(),
                target
            );
            builder.add(targetNode);
          }
    } catch (BuildFileParseException | BuildTargetException e) {
      params.getBuckEventBus().post(ConsoleEvent.severe(
          MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
      return 1;
    }
    ImmutableList<TargetNode<?, ?>> targetNodes = builder.build();

    if (shouldGenerateJsonOutput()) {
      printJsonFlavors(targetNodes, params);
    } else {
      printFlavors(targetNodes, params);
    }

    return 0;
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }

  private void printFlavors(
      ImmutableList<TargetNode<?, ?>> targetNodes,
      CommandRunnerParams params) {
    DirtyPrintStreamDecorator stdout = params.getConsole().getStdOut();
    for (TargetNode<?, ?> node : targetNodes) {
      Description<?> description = node.getDescription();
      stdout.println(node.getBuildTarget().getFullyQualifiedName());
      if (description instanceof Flavored) {
        Optional<ImmutableSet<FlavorDomain<?>>> flavorDomains =
            ((Flavored) description).flavorDomains();
        if (flavorDomains.isPresent()) {
          for (FlavorDomain<?> domain : flavorDomains.get()) {
            stdout.printf(" %s\n", domain.getName());
            for (Flavor flavor : domain.getFlavors()) {
              stdout.printf("  %s\n", flavor.getName());
            }
          }
        } else {
          stdout.println(" unknown");
        }
      } else {
        stdout.println(" no flavors");
      }
    }
  }

  private void printJsonFlavors(
      ImmutableList<TargetNode<?, ?>> targetNodes,
      CommandRunnerParams params) throws IOException {
    DirtyPrintStreamDecorator stdout = params.getConsole().getStdOut();
    SortedMap<String, SortedMap<String, List<String>>> targetsJson = new TreeMap<>();
    for (TargetNode<?, ?> node : targetNodes) {
      Description<?> description = node.getDescription();
      SortedMap<String, List<String>> flavorDomainsJson = new TreeMap<>();

      if (description instanceof Flavored) {
        Optional<ImmutableSet<FlavorDomain<?>>> flavorDomains =
            ((Flavored) description).flavorDomains();
        if (flavorDomains.isPresent()) {
          for (FlavorDomain<?> domain : flavorDomains.get()) {
            List<String> flavorsJson =
            domain.getFlavors().stream()
                .map(Flavor::getName)
                .collect(Collectors.toList());
            flavorDomainsJson.put(domain.getName(), flavorsJson);
          }
        } else {
          flavorDomainsJson.put("unknown", new ArrayList<>());
        }
      }
      String targetName = node.getBuildTarget().getFullyQualifiedName();
      targetsJson.put(targetName, flavorDomainsJson);
    }

    params.getObjectMapper().writeValue(stdout, targetsJson);
  }
}
