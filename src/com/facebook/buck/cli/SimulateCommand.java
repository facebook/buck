/*
 * Copyright 2015-present Facebook, Inc.
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
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.Pair;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.simulate.BuildSimulator;
import com.facebook.buck.simulate.SimulateReport;
import com.facebook.buck.simulate.SimulateTimes;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.common.base.Charsets;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.io.Files;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.logging.Level;

public class SimulateCommand extends AbstractCommand {
  private static final Logger LOG = Logger.get(SimulateCommand.class);

  private static final String TIMES_FILE_STRING_ARG = "--times-file";
  private static final String TIME_AGGREGATE_ARG = "--time-aggregate";
  private static final String REPORT_FILE_STRING_ARG = "--report-file";
  private static final String RULE_FALLBACK_TIME_MILLIS_ARG = "--rule-fallback-time-millis";

  @Option(
      name = REPORT_FILE_STRING_ARG,
      usage = "Simulation report is written to this JSON file. default=buck_simulate_report.json")
  private String simulateReportFile = "buck_simulate_report.json";

  @Option(
      name = TIMES_FILE_STRING_ARG,
  usage = "JSON file containing BuildTarget simulation times.")
  private String simulateTimesFile = "";

  @Option(
      name = RULE_FALLBACK_TIME_MILLIS_ARG,
      usage = "If the duration of a given rule cannot be found in the supplied JSON file, " +
          "this value will be used instead. default=10ms")
  private long ruleFallbackTimeMillis = 10;

  @Option(
      name = TIME_AGGREGATE_ARG,
      usage = "The time aggregate measurement to use (eg avg, p90, p95, p99). default=avg")
  private String simulateTimeAggregate = "avg";

  @Argument
  private List<String> arguments = Lists.newArrayList();

  @Override
  public int runWithoutHelp(CommandRunnerParams params) throws IOException, InterruptedException {

    // Create a BuildCommand to generate the ActionGraph.
    BuildCommand buildCommand = new BuildCommand(arguments);
    Pair<ActionGraph, BuildRuleResolver> actionGraphAndResolver =
        buildCommand.createActionGraphAndResolver(params);
    if (actionGraphAndResolver == null) {
      return 1;
    }

    SimulateTimes times = Strings.isNullOrEmpty(simulateTimesFile) ?
        SimulateTimes.createEmpty(ruleFallbackTimeMillis) :
        SimulateTimes.createFromJsonFile(
            params.getObjectMapper(),
            simulateTimesFile,
            simulateTimeAggregate,
            ruleFallbackTimeMillis);

    // Run the simulation with the generated ActionGraph.
    BuildSimulator simulator = new BuildSimulator(
        times,
        actionGraphAndResolver.getFirst(),
        params.getBuckConfig().getNumThreads());
    SimulateReport report = simulator.simulateBuild(
        params.getClock().currentTimeMillis(),
        buildCommand.getBuildTargets());

    // Write down results.
    outputReport(params.getObjectMapper(), report);
    String finishedMsg = String.format(
        "Simulation report was successfully written to [%s]",
        simulateReportFile);

    // TODO(ruibm): Update the SuperConsole to print progress properly like when running the build.
    params.getBuckEventBus().post(ConsoleEvent.create(Level.INFO, finishedMsg));
    return 0;
  }

  private void outputReport(ObjectMapper jsonConverter, SimulateReport report)
      throws IOException {
    // Pretty print the output.
    jsonConverter.enable(SerializationFeature.INDENT_OUTPUT);
    jsonConverter.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    jsonConverter.setPropertyNamingStrategy(
        PropertyNamingStrategy.CAMEL_CASE_TO_LOWER_CASE_WITH_UNDERSCORES);

    String json = jsonConverter.writeValueAsString(report);
    LOG.verbose("SimulateReport => [%s]", json);
    Files.write(json, new File(simulateReportFile), Charsets.UTF_8);
  }

  @Override
  public String getShortDescription() {
    return "timed simulation of a build without running the steps";
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }

  @Override
  protected ImmutableList<String> getOptions() {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    builder.addAll(super.getOptions());
    if (simulateTimesFile != null) {
      builder.add(TIMES_FILE_STRING_ARG);
      builder.add(simulateTimesFile);
    }

    if (simulateReportFile != null) {
      builder.add(REPORT_FILE_STRING_ARG);
      builder.add(simulateReportFile);
    }

    if (simulateTimeAggregate != null) {
      builder.add(TIME_AGGREGATE_ARG);
      builder.add(simulateTimeAggregate);
    }

    builder.add(RULE_FALLBACK_TIME_MILLIS_ARG);
    builder.add(Long.toString(ruleFallbackTimeMillis));

    return builder.build();
  }
}
