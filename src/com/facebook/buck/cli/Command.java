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

import com.google.common.base.Optional;

import java.io.IOException;

public enum Command {

  AUDIT(
      "lists the inputs for the specified target",
      new AuditCommandRunner()),
  BUILD(
      "builds the specified target",
      new BuildCommand()),
  CLEAN(
      "deletes any generated files",
      new CleanCommand()),
  INSTALL(
      "builds and installs an APK",
      new InstallCommand()),
  PROJECT(
      "generates project configuration files for an IDE",
      new ProjectCommand()),
  TARGETS(
      "prints the list of buildable targets",
      new TargetsCommand()),
  TEST(
      "builds and runs the tests for the specified target",
      new TestCommand()),
  UNINSTALL(
      "uninstalls an APK",
      new UninstallCommand()),
  ;

  private final String shortDescription;
  private final CommandRunner commandRunner;

  private Command(
      String shortDescription,
      CommandRunner commandRunner) {
    this.shortDescription = shortDescription;
    this.commandRunner = commandRunner;
  }

  public String getShortDescription() {
    return shortDescription;
  }

  public int execute(BuckConfig buckConfig, String[] args) throws IOException {
    return commandRunner.runCommand(buckConfig, args);
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
