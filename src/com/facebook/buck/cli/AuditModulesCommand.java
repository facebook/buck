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

package com.facebook.buck.cli;

import com.facebook.buck.core.module.BuckModuleManager;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.json.ObjectMappers;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringWriter;
import org.kohsuke.args4j.Option;

/**
 * Prints the following information about all modules:
 *
 * <ul>
 *   <li>id,
 *   <li>hash,
 *   <li>dependencies.
 * </ul>
 */
public class AuditModulesCommand extends AbstractCommand {

  @Option(name = "--json", usage = "Output in JSON format")
  private boolean generateJsonOutput;

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params)
      throws IOException, InterruptedException {

    collectAndDumpModuleInformation(
        params.getConsole(), params.getBuckModuleManager(), generateJsonOutput);

    return ExitCode.SUCCESS;
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }

  @Override
  public String getShortDescription() {
    return "List information about Buck modules";
  }

  @VisibleForTesting
  static void collectAndDumpModuleInformation(
      Console console, BuckModuleManager moduleManager, boolean generateJsonOutput) {
    ImmutableList<AuditModuleInformation> modules = collectModuleInformation(moduleManager);

    if (generateJsonOutput) {
      dumpModuleInformationInJsonFormat(console, modules);
    } else {
      dumpModuleInformationInRawFormat(console, modules);
    }
  }

  private static ImmutableList<AuditModuleInformation> collectModuleInformation(
      BuckModuleManager moduleManager) {
    ImmutableList.Builder<AuditModuleInformation> modules = ImmutableList.builder();

    for (String moduleId : moduleManager.getModuleIds()) {
      modules.add(
          new AuditModuleInformation(
              moduleId,
              moduleManager.getModuleHash(moduleId),
              moduleManager.getModuleDependencies(moduleId)));
    }

    return modules.build();
  }

  private static void dumpModuleInformationInJsonFormat(
      Console console, ImmutableList<AuditModuleInformation> modules) {
    StringWriter stringWriter = new StringWriter();
    try {
      ObjectMappers.WRITER.withDefaultPrettyPrinter().writeValue(stringWriter, modules);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    console.getStdOut().println(stringWriter.getBuffer().toString());
  }

  private static void dumpModuleInformationInRawFormat(
      Console console, ImmutableList<AuditModuleInformation> modules) {

    modules.forEach(module -> dumpModuleInformationInRawFormat(console.getStdOut(), module));
  }

  private static void dumpModuleInformationInRawFormat(
      PrintStream stdout, AuditModuleInformation module) {
    stdout.println(String.format("Module id: %s", module.id));
    stdout.println(String.format("Module hash: %s", module.hash));
    stdout.println(
        String.format("Module dependencies: %s", Joiner.on(", ").join(module.dependencies)));
    stdout.println();
  }

  private static class AuditModuleInformation {
    public final String id;
    public final String hash;
    public final ImmutableSortedSet<String> dependencies;

    private AuditModuleInformation(
        String id, String hash, ImmutableSortedSet<String> dependencies) {
      this.id = id;
      this.hash = hash;
      this.dependencies = dependencies;
    }
  }
}
