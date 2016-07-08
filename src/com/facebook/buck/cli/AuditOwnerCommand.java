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
import com.facebook.buck.query.QueryException;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.util.MoreExceptions;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import com.google.common.collect.TreeMultimap;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public class AuditOwnerCommand extends AbstractCommand {

  @VisibleForTesting
  static final class OwnersReport {
    final ImmutableSetMultimap<TargetNode<?>, Path> owners;
    final ImmutableSet<Path> inputsWithNoOwners;
    final ImmutableSet<String> nonExistentInputs;
    final ImmutableSet<String> nonFileInputs;

    public OwnersReport(SetMultimap<TargetNode<?>, Path> owners,
        Set<Path> inputsWithNoOwners,
        Set<String> nonExistentInputs,
        Set<String> nonFileInputs) {
      this.owners = ImmutableSetMultimap.copyOf(owners);
      this.inputsWithNoOwners = ImmutableSet.copyOf(inputsWithNoOwners);
      this.nonExistentInputs = ImmutableSet.copyOf(nonExistentInputs);
      this.nonFileInputs = ImmutableSet.copyOf(nonFileInputs);
    }

    public static OwnersReport emptyReport() {
      return new OwnersReport(
          ImmutableSetMultimap.<TargetNode<?>, Path>of(),
          Sets.<Path>newHashSet(),
          Sets.<String>newHashSet(),
          Sets.<String>newHashSet());
    }

    public OwnersReport updatedWith(OwnersReport other) {
      SetMultimap<TargetNode<?>, Path> updatedOwners =
          TreeMultimap.create(owners);
      updatedOwners.putAll(other.owners);

      return new OwnersReport(
          updatedOwners,
          Sets.intersection(inputsWithNoOwners, other.inputsWithNoOwners),
          Sets.union(nonExistentInputs, other.nonExistentInputs),
          Sets.union(nonFileInputs, other.nonFileInputs));
    }
  }

  @Option(name = "--json",
      usage = "Output in JSON format")
  private boolean generateJsonOutput;

  public boolean shouldGenerateJsonOutput() {
    return generateJsonOutput;
  }

  @Argument
  private List<String> arguments = Lists.newArrayList();

  public List<String> getArguments() {
    return arguments;
  }

  @VisibleForTesting
  void setArguments(List<String> arguments) {
    this.arguments = arguments;
  }

  @Override
  public int runWithoutHelp(CommandRunnerParams params) throws IOException, InterruptedException {
    if (params.getConsole().getAnsi().isAnsiTerminal()) {
      params.getBuckEventBus().post(ConsoleEvent.info(
          "'buck audit owner' is deprecated. Please use 'buck query' instead. e.g.\n\t%s\n\n" +
              "The query language is documented at https://buckbuild.com/command/query.html",
          QueryCommand.buildAuditOwnerQueryExpression(getArguments(), shouldGenerateJsonOutput())));
    }

    BuckQueryEnvironment env = new BuckQueryEnvironment(params, getEnableParserProfiling());
    try (CommandThreadManager pool = new CommandThreadManager(
        "Audit",
        getConcurrencyLimit(params.getBuckConfig()))) {
      return QueryCommand.runMultipleQuery(
          params,
          env,
          pool.getExecutor(),
          "owner('%s')",
          getArguments(),
          shouldGenerateJsonOutput());
    } catch (QueryException e) {
      if (e.getCause() instanceof InterruptedException) {
        throw (InterruptedException) e.getCause();
      }
      params.getBuckEventBus().post(
          ConsoleEvent.severe(MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
      return 1;
    }
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }

  @Override
  public String getShortDescription() {
    return "prints targets that own specified files";
  }

}
