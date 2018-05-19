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

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.UserFlavor;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.DirtyPrintStreamDecorator;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.MoreExceptions;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.json.ObjectMappers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Ordering;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

/** List flavor domains for build targets. */
public class AuditFlavorsCommand extends AbstractCommand {

  @Option(name = "--json", usage = "Output in JSON format")
  private boolean generateJsonOutput;

  public boolean shouldGenerateJsonOutput() {
    return generateJsonOutput;
  }

  @Override
  public String getShortDescription() {
    return "List flavor domains for build targets.";
  }

  @Argument private List<String> arguments = new ArrayList<>();

  public List<String> getArguments() {
    return arguments;
  }

  private ImmutableList<String> getArgumentsFormattedAsBuildTargets(BuckConfig buckConfig) {
    return getCommandLineBuildTargetNormalizer(buckConfig).normalizeAll(getArguments());
  }

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params)
      throws IOException, InterruptedException {
    ImmutableSet<BuildTarget> targets =
        getArgumentsFormattedAsBuildTargets(params.getBuckConfig())
            .stream()
            .map(
                input ->
                    BuildTargetParser.INSTANCE.parse(
                        input,
                        BuildTargetPatternParser.fullyQualified(),
                        params.getCell().getCellPathResolver()))
            .collect(ImmutableSet.toImmutableSet());

    if (targets.isEmpty()) {
      throw new CommandLineException("must specify at least one build target");
    }

    ImmutableList.Builder<TargetNode<?, ?>> builder = ImmutableList.builder();
    try (CommandThreadManager pool =
        new CommandThreadManager("Audit", getConcurrencyLimit(params.getBuckConfig()))) {
      for (BuildTarget target : targets) {
        TargetNode<?, ?> targetNode =
            params
                .getParser()
                .getTargetNode(
                    params.getBuckEventBus(),
                    params.getCell(),
                    getEnableParserProfiling(),
                    pool.getListeningExecutorService(),
                    target);
        builder.add(targetNode);
      }
    } catch (BuildFileParseException e) {
      params
          .getBuckEventBus()
          .post(ConsoleEvent.severe(MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
      return ExitCode.PARSE_ERROR;
    }
    ImmutableList<TargetNode<?, ?>> targetNodes = builder.build();

    if (shouldGenerateJsonOutput()) {
      printJsonFlavors(targetNodes, params);
    } else {
      printFlavors(targetNodes, params);
    }

    return ExitCode.SUCCESS;
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }

  private void printFlavors(
      ImmutableList<TargetNode<?, ?>> targetNodes, CommandRunnerParams params) {
    DirtyPrintStreamDecorator stdout = params.getConsole().getStdOut();
    for (TargetNode<?, ?> node : targetNodes) {
      DescriptionWithTargetGraph<?> description = node.getDescription();
      stdout.println(node.getBuildTarget().getFullyQualifiedName());
      if (description instanceof Flavored) {
        Optional<ImmutableSet<FlavorDomain<?>>> flavorDomains =
            ((Flavored) description).flavorDomains();
        if (flavorDomains.isPresent()) {
          for (FlavorDomain<?> domain : flavorDomains.get()) {
            ImmutableSet<UserFlavor> userFlavors =
                RichStream.from(domain.getFlavors().stream())
                    .filter(UserFlavor.class)
                    .collect(ImmutableSet.toImmutableSet());
            if (userFlavors.isEmpty()) {
              continue;
            }
            stdout.printf(" %s\n", domain.getName());
            for (UserFlavor flavor : userFlavors) {
              String flavorLine = String.format("  %s", flavor.getName());
              String flavorDescription = flavor.getDescription();
              if (flavorDescription.length() > 0) {
                flavorLine += String.format(" -> %s", flavorDescription);
              }
              flavorLine += "\n";
              stdout.printf(flavorLine);
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
      ImmutableList<TargetNode<?, ?>> targetNodes, CommandRunnerParams params) throws IOException {
    DirtyPrintStreamDecorator stdout = params.getConsole().getStdOut();
    SortedMap<String, SortedMap<String, SortedMap<String, String>>> targetsJson = new TreeMap<>();
    for (TargetNode<?, ?> node : targetNodes) {
      DescriptionWithTargetGraph<?> description = node.getDescription();
      SortedMap<String, SortedMap<String, String>> flavorDomainsJson = new TreeMap<>();

      if (description instanceof Flavored) {
        Optional<ImmutableSet<FlavorDomain<?>>> flavorDomains =
            ((Flavored) description).flavorDomains();
        if (flavorDomains.isPresent()) {
          for (FlavorDomain<?> domain : flavorDomains.get()) {
            ImmutableSet<UserFlavor> userFlavors =
                RichStream.from(domain.getFlavors().stream())
                    .filter(UserFlavor.class)
                    .collect(ImmutableSet.toImmutableSet());
            if (userFlavors.isEmpty()) {
              continue;
            }
            SortedMap<String, String> flavorsJson =
                userFlavors
                    .stream()
                    .collect(
                        ImmutableSortedMap.toImmutableSortedMap(
                            Ordering.natural(), UserFlavor::getName, UserFlavor::getDescription));
            flavorDomainsJson.put(domain.getName(), flavorsJson);
          }
        } else {
          flavorDomainsJson.put("unknown", new TreeMap<>());
        }
      }
      String targetName = node.getBuildTarget().getFullyQualifiedName();
      targetsJson.put(targetName, flavorDomainsJson);
    }

    ObjectMappers.WRITER.writeValue(stdout, targetsJson);
  }
}
