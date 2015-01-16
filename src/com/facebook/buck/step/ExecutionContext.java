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

package com.facebook.buck.step;

import com.facebook.buck.android.NoAndroidSdkException;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ThrowableConsoleEvent;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.java.JavaPackageFinder;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.android.AndroidPlatformTarget;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.environment.Platform;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;

import java.io.PrintStream;
import java.nio.file.Path;

import javax.annotation.Nullable;

public class ExecutionContext {

  private final Verbosity verbosity;
  private final ProjectFilesystem projectFilesystem;
  private final Console console;
  private final Optional<AndroidPlatformTarget> androidPlatformTarget;
  private final Optional<TargetDevice> targetDevice;
  private final long defaultTestTimeoutMillis;
  private final boolean isCodeCoverageEnabled;
  private final boolean isDebugEnabled;
  private final ProcessExecutor processExecutor;
  private final BuckEventBus eventBus;
  private final Platform platform;
  private final ImmutableMap<String, String> environment;
  private final JavaPackageFinder javaPackageFinder;
  private final ObjectMapper objectMapper;

  private ExecutionContext(
      ProjectFilesystem projectFilesystem,
      Console console,
      Optional<AndroidPlatformTarget> androidPlatformTarget,
      Optional<TargetDevice> targetDevice,
      long defaultTestTimeoutMillis,
      boolean isCodeCoverageEnabled,
      boolean isDebugEnabled,
      ProcessExecutor processExecutor,
      BuckEventBus eventBus,
      Platform platform,
      ImmutableMap<String, String> environment,
      JavaPackageFinder javaPackageFinder,
      ObjectMapper objectMapper) {
    this.verbosity = console.getVerbosity();
    this.projectFilesystem = projectFilesystem;
    this.console = console;
    this.androidPlatformTarget = androidPlatformTarget;
    this.targetDevice = targetDevice;
    this.defaultTestTimeoutMillis = defaultTestTimeoutMillis;
    this.isCodeCoverageEnabled = isCodeCoverageEnabled;
    this.isDebugEnabled = isDebugEnabled;
    this.processExecutor = processExecutor;
    this.eventBus = eventBus;
    this.platform = platform;
    this.environment = environment;
    this.javaPackageFinder = javaPackageFinder;
    this.objectMapper = objectMapper;
  }

  /**
   * @return A clone of this {@link ExecutionContext} with {@code stdout} and {@code stderr}
   *    redirected to the provided {@link PrintStream}s.
   */
  public ExecutionContext createSubContext(PrintStream newStdout, PrintStream newStderr) {
    Console console = new Console(
        this.console.getVerbosity(),
        newStdout,
        newStderr,
        this.console.getAnsi());

    return new ExecutionContext(
        getProjectFilesystem(),
        console,
        getAndroidPlatformTargetOptional(),
        getTargetDeviceOptional(),
        getDefaultTestTimeoutMillis(),
        isCodeCoverageEnabled(),
        isDebugEnabled,
        new ProcessExecutor(console),
        eventBus,
        platform,
        this.environment,
        this.javaPackageFinder,
        this.objectMapper);
  }

  public void logError(Throwable error, String msg, Object... formatArgs) {
    eventBus.post(ThrowableConsoleEvent.create(error, msg, formatArgs));
  }

  public void postEvent(BuckEvent event) {
    eventBus.post(event);
  }

  public Verbosity getVerbosity() {
    return verbosity;
  }

  public ProjectFilesystem getProjectFilesystem() {
    return projectFilesystem;
  }

  public Path getProjectDirectoryRoot() {
    return projectFilesystem.getRootPath();
  }

  public Console getConsole() {
    return console;
  }

  public PrintStream getStdErr() {
    return console.getStdErr();
  }

  public PrintStream getStdOut() {
    return console.getStdOut();
  }

  public Ansi getAnsi() {
    return console.getAnsi();
  }

  public BuckEventBus getBuckEventBus() {
    return eventBus;
  }

  public Platform getPlatform() {
    return platform;
  }

  public JavaPackageFinder getJavaPackageFinder() {
    return javaPackageFinder;
  }

  public ObjectMapper getObjectMapper() {
    return objectMapper;
  }

  /**
   * Returns the {@link AndroidPlatformTarget}, if present. If not, throws a
   * {@link NoAndroidSdkException}. Use this when your logic requires the user to specify the
   * location of an Android SDK. A user who is building a "pure Java" (i.e., not Android) project
   * using Buck should never have to exercise this code path.
   * <p>
   * If the location of an Android SDK is optional, then use
   * {@link #getAndroidPlatformTargetOptional()}.
   * @throws NoAndroidSdkException if no AndroidPlatformTarget is available
   */
  public AndroidPlatformTarget getAndroidPlatformTarget() throws NoAndroidSdkException {
    if (androidPlatformTarget.isPresent()) {
      return androidPlatformTarget.get();
    } else {
      throw new NoAndroidSdkException();
    }
  }

  /**
   * Returns an {@link AndroidPlatformTarget} if the user specified one via {@code local.properties}
   * or some other mechanism. If the user failed to specify one, {@link Optional#absent()} will be
   * returned.
   */
  public Optional<AndroidPlatformTarget> getAndroidPlatformTargetOptional() {
    return androidPlatformTarget;
  }

  public Optional<TargetDevice> getTargetDeviceOptional() {
    return targetDevice;
  }

  public long getDefaultTestTimeoutMillis() {
    return defaultTestTimeoutMillis;
  }

  public boolean isCodeCoverageEnabled() {
    return isCodeCoverageEnabled;
  }

  public boolean isDebugEnabled() {
    return isDebugEnabled;
  }

  public String getPathToAdbExecutable() throws NoAndroidSdkException {
    return getAndroidPlatformTarget().getAdbExecutable().toString();
  }

  public ProcessExecutor getProcessExecutor() {
    return processExecutor;
  }

  public static Builder builder() {
    return new Builder();
  }

  public ImmutableMap<String, String> getEnvironment() {
    return environment;
  }

  public BuildId getBuildId() {
    return eventBus.getBuildId();
  }

  public static class Builder {

    @Nullable private ProjectFilesystem projectFilesystem = null;
    @Nullable private Console console = null;
    private Optional<AndroidPlatformTarget> androidPlatformTarget = Optional.absent();
    private Optional<TargetDevice> targetDevice = Optional.absent();
    private long defaultTestTimeoutMillis = 0L;
    private boolean isCodeCoverageEnabled = false;
    private boolean isDebugEnabled = false;
    @Nullable private ProcessExecutor processExecutor;
    @Nullable private BuckEventBus eventBus = null;
    @Nullable private Platform platform = null;
    @Nullable private ImmutableMap<String, String> environment = null;
    @Nullable private JavaPackageFinder javaPackageFinder = null;
    @Nullable private ObjectMapper objectMapper = null;

    private Builder() {}

    public ExecutionContext build() {
      return new ExecutionContext(
          Preconditions.checkNotNull(projectFilesystem),
          Preconditions.checkNotNull(console),
          androidPlatformTarget,
          targetDevice,
          defaultTestTimeoutMillis,
          isCodeCoverageEnabled,
          isDebugEnabled,
          Preconditions.checkNotNull(processExecutor),
          Preconditions.checkNotNull(eventBus),
          Preconditions.checkNotNull(platform),
          Preconditions.checkNotNull(environment),
          Preconditions.checkNotNull(javaPackageFinder),
          Preconditions.checkNotNull(objectMapper));
    }

    public Builder setExecutionContext(ExecutionContext executionContext) {
      setProjectFilesystem(executionContext.getProjectFilesystem());
      setConsole(executionContext.getConsole());
      setAndroidPlatformTarget(executionContext.getAndroidPlatformTargetOptional());
      setTargetDevice(executionContext.getTargetDeviceOptional());
      setDefaultTestTimeoutMillis(executionContext.getDefaultTestTimeoutMillis());
      setCodeCoverageEnabled(executionContext.isCodeCoverageEnabled());
      setDebugEnabled(executionContext.isDebugEnabled());
      setEventBus(executionContext.getBuckEventBus());
      setPlatform(executionContext.getPlatform());
      setEnvironment(executionContext.getEnvironment());
      setJavaPackageFinder(executionContext.getJavaPackageFinder());
      setObjectMapper(executionContext.getObjectMapper());
      return this;
    }

    public Builder setProjectFilesystem(ProjectFilesystem projectFilesystem) {
      this.projectFilesystem = projectFilesystem;
      return this;
    }

    public Builder setConsole(Console console) {
      this.console = console;
      if (this.processExecutor == null) {
        this.processExecutor = new ProcessExecutor(console);
      }
      return this;
    }

    public Builder setAndroidPlatformTarget(Optional<AndroidPlatformTarget> androidPlatformTarget) {
      this.androidPlatformTarget = androidPlatformTarget;
      return this;
    }

    public Builder setTargetDevice(Optional<TargetDevice> targetDevice) {
      this.targetDevice = targetDevice;
      return this;
    }

    /** Specify 0 for no timeout. */
    public Builder setDefaultTestTimeoutMillis(long defaultTestTimeoutMillis) {
      Preconditions.checkArgument(
          defaultTestTimeoutMillis >= 0,
          "Default timeout cannot be negative.");
      this.defaultTestTimeoutMillis = defaultTestTimeoutMillis;
      return this;
    }

    public Builder setCodeCoverageEnabled(boolean isCodeCoverageEnabled) {
      this.isCodeCoverageEnabled = isCodeCoverageEnabled;
      return this;
    }

    public Builder setDebugEnabled(boolean isDebugEnabled) {
      this.isDebugEnabled = isDebugEnabled;
      return this;
    }

    public Builder setProcessExecutor(ProcessExecutor processExecutor) {
      this.processExecutor = processExecutor;
      return this;
    }

    public Builder setEventBus(BuckEventBus eventBus) {
      this.eventBus = eventBus;
      return this;
    }

    public Builder setPlatform(Platform platform) {
      this.platform = platform;
      return this;
    }

    public Builder setEnvironment(ImmutableMap<String, String> environment) {
      this.environment = environment;
      return this;
    }

    public Builder setJavaPackageFinder(JavaPackageFinder javaPackageFinder) {
      this.javaPackageFinder = javaPackageFinder;
      return this;
    }

    public Builder setObjectMapper(ObjectMapper objectMapper) {
      this.objectMapper = objectMapper;
      return this;
    }
  }
}
