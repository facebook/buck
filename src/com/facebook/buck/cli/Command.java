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

import com.facebook.buck.rules.ArtifactCache;
import com.google.common.base.Optional;
import com.google.common.base.Throwables;

import java.io.IOException;

public enum Command {

  AUDIT(
      "lists the inputs for the specified target",
      AuditCommandRunner.class),
  BUILD(
      "builds the specified target",
      BuildCommand.class),
  CLEAN(
      "deletes any generated files",
      CleanCommand.class),
  INSTALL(
      "builds and installs an APK",
      InstallCommand.class),
  PROJECT(
      "generates project configuration files for an IDE",
      ProjectCommand.class),
  TARGETS(
      "prints the list of buildable targets",
      TargetsCommand.class),
  TEST(
      "builds and runs the tests for the specified target",
      TestCommand.class),
  UNINSTALL(
      "uninstalls an APK",
      UninstallCommand.class),
  ;

  private final String shortDescription;
  private final Class<? extends CommandRunner> commandRunnerClass;

  private Command(
      String shortDescription,
      Class<? extends CommandRunner> commandRunnerClass) {
    this.shortDescription = shortDescription;
    this.commandRunnerClass = commandRunnerClass;
  }

  public String getShortDescription() {
    return shortDescription;
  }

  public int execute(BuckConfig buckConfig, String[] args) throws IOException {
    try {
      CommandRunner commandRunner =
          commandRunnerClass.getDeclaredConstructor(ArtifactCache.class)
          .newInstance(buckConfig.getArtifactCache());
      return commandRunner.runCommand(buckConfig, args);
    } catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }

  /**
   * @return a non-empty {@link Optional} if {@code name} corresponds to a command; otherwise,
   *   an empty {@link Optional}. This will return the latter if the user tries to run something
   *   like {@code buck --help}.
   */
  public static Optional<Command> getCommandForName(String name) {
    Command command;
    try {
      command = valueOf(name.toUpperCase());
    } catch (IllegalArgumentException e) {
      return Optional.absent();
    }
    return Optional.of(command);
  }

}
