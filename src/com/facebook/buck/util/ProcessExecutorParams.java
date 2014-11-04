/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.util;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;

import com.google.common.collect.ImmutableList;

import java.io.File;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Value type passed to {@link ProcessExecutor} to launch a process.
 */
public class ProcessExecutorParams {
  private final ImmutableList<String> command;
  private final Optional<File> directory;
  private final Optional<Map<String, String>> environment;
  private final Optional<ProcessBuilder.Redirect> redirectInput;
  private final Optional<ProcessBuilder.Redirect> redirectOutput;
  private final Optional<ProcessBuilder.Redirect> redirectError;

  private ProcessExecutorParams(
      ImmutableList<String> command,
      Optional<File> directory,
      Optional<Map<String, String>> environment,
      Optional<ProcessBuilder.Redirect> redirectInput,
      Optional<ProcessBuilder.Redirect> redirectOutput,
      Optional<ProcessBuilder.Redirect> redirectError) {
    this.command = command;
    Preconditions.checkArgument(!command.isEmpty());
    this.directory = directory;
    this.environment = environment;
    this.redirectInput = redirectInput;
    this.redirectOutput = redirectOutput;
    this.redirectError = redirectError;
  }

  /**
   * The command and arguments to launch.
   */
  public ImmutableList<String> getCommand() {
    return command;
  }

  /**
   * If present, the current working directory for the launched process.
   */
  public Optional<File> getDirectory() {
    return directory;
  }

  /**
   * If present, the map of environment variables used for the launched
   * process. Otherwise, inherits the current process's environment.
   */
  public Optional<Map<String, String>> getEnvironment() {
    return environment;
  }

  /**
   * If present, redirects stdout for the process to this location.
   * Otherwise, opens a pipe for stdout.
   */
  public Optional<ProcessBuilder.Redirect> getRedirectInput() {
    return redirectInput;
  }

  /**
   * If present, redirects stdin for the process to this location.
   * Otherwise, opens a pipe for stdin.
   */
  public Optional<ProcessBuilder.Redirect> getRedirectOutput() {
    return redirectOutput;
  }

  /**
   * If present, redirects stderr for the process to this location.
   * Otherwise, opens a pipe for stderr.
   */
  public Optional<ProcessBuilder.Redirect> getRedirectError() {
    return redirectError;
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        command,
        directory,
        environment,
        redirectInput,
        redirectOutput,
        redirectError);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }

    if (!(other instanceof ProcessExecutorParams)) {
      return false;
    }

    ProcessExecutorParams that = (ProcessExecutorParams) other;

    return
      Objects.equals(command, that.command) &&
      Objects.equals(directory, that.directory) &&
      Objects.equals(environment, that.environment) &&
      Objects.equals(redirectInput, that.redirectInput) &&
      Objects.equals(redirectOutput, that.redirectOutput) &&
      Objects.equals(redirectError, that.redirectError);
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * Builds {@link ProcessExecutorParams} instances.
   */
  public static class Builder {
    private ImmutableList<String> command = ImmutableList.of();
    private Optional<File> directory = Optional.absent();
    private Optional<Map<String, String>> environment = Optional.absent();
    private Optional<ProcessBuilder.Redirect> redirectInput = Optional.absent();
    private Optional<ProcessBuilder.Redirect> redirectOutput = Optional.absent();
    private Optional<ProcessBuilder.Redirect> redirectError = Optional.absent();

    public Builder setCommand(List<String> command) {
      this.command = ImmutableList.copyOf(command);
      return this;
    }

    public Builder setDirectory(File directory) {
      this.directory = Optional.of(directory);
      return this;
    }

    public Builder setEnvironment(Map<String, String> environment) {
      this.environment = Optional.of(environment);
      return this;
    }

    public Builder setRedirectInput(ProcessBuilder.Redirect redirectInput) {
      this.redirectInput = Optional.of(redirectInput);
      return this;
    }

    public Builder setRedirectOutput(ProcessBuilder.Redirect redirectOutput) {
      this.redirectOutput = Optional.of(redirectOutput);
      return this;
    }

    public Builder setRedirectError(ProcessBuilder.Redirect redirectError) {
      this.redirectError = Optional.of(redirectError);
      return this;
    }

    public ProcessExecutorParams build() {
      return new ProcessExecutorParams(
          command,
          directory,
          environment,
          redirectInput,
          redirectOutput,
          redirectError);
    }
  }
}
