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
import com.facebook.buck.event.ThrowableLogEvent;
import com.facebook.buck.util.AndroidPlatformTarget;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;

import java.io.File;
import java.io.PrintStream;

public class ExecutionContext {

  private final Verbosity verbosity;
  private final ProjectFilesystem projectFilesystem;
  private final Console console;
  private final Optional<AndroidPlatformTarget> androidPlatformTarget;
  private final Optional<TargetDevice> targetDevice;
  private final long defaultTestTimeoutMillis;
  private final boolean isCodeCoverageEnabled;
  private final boolean isJacocoEnabled;
  private final boolean isDebugEnabled;
  private final ProcessExecutor processExecutor;
  private final BuckEventBus eventBus;
  private final Platform platform;

  private ExecutionContext(
      ProjectFilesystem projectFilesystem,
      Console console,
      Optional<AndroidPlatformTarget> androidPlatformTarget,
      Optional<TargetDevice> targetDevice,
      long defaultTestTimeoutMillis,
      boolean isCodeCoverageEnabled,
      boolean isJacocoEnabled,
      boolean isDebugEnabled,
      BuckEventBus eventBus,
      Platform platform) {
    this.verbosity = Preconditions.checkNotNull(console).getVerbosity();
    this.projectFilesystem = Preconditions.checkNotNull(projectFilesystem);
    this.console = Preconditions.checkNotNull(console);
    this.androidPlatformTarget = Preconditions.checkNotNull(androidPlatformTarget);
    this.targetDevice = Preconditions.checkNotNull(targetDevice);
    this.defaultTestTimeoutMillis = defaultTestTimeoutMillis;
    this.isCodeCoverageEnabled = isCodeCoverageEnabled;
    this.isJacocoEnabled = isJacocoEnabled;
    this.isDebugEnabled = isDebugEnabled;
    this.processExecutor = new ProcessExecutor(console);
    this.eventBus = Preconditions.checkNotNull(eventBus);
    this.platform = Preconditions.checkNotNull(platform);
  }

  /**
   * @return A clone of this {@link ExecutionContext} with {@code stdout} and {@code stderr}
   *    redirected to the provided {@link PrintStream}s.
   */
  public ExecutionContext createSubContext(PrintStream newStdout, PrintStream newStderr) {
    return new ExecutionContext(
        getProjectFilesystem(),
        new Console(console.getVerbosity(), newStdout, newStderr, console.getAnsi()),
        getAndroidPlatformTargetOptional(),
        getTargetDeviceOptional(),
        getDefaultTestTimeoutMillis(),
        isCodeCoverageEnabled(),
        isJacocoEnabled(),
        isDebugEnabled,
        eventBus,
        platform);
  }

  public void logError(Throwable error, String msg, Object... formatArgs) {
    eventBus.post(ThrowableLogEvent.create(error, msg, formatArgs));
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

  public File getProjectDirectoryRoot() {
    return projectFilesystem.getProjectRoot();
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

  public boolean isJacocoEnabled() {
    return isJacocoEnabled;
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

  public static class Builder {

    private ProjectFilesystem projectFilesystem = null;
    private Console console = null;
    private Optional<AndroidPlatformTarget> androidPlatformTarget = Optional.absent();
    private Optional<TargetDevice> targetDevice = Optional.absent();
    private long defaultTestTimeoutMillis = 0L;
    private boolean isCodeCoverageEnabled = false;
    private boolean isJacocoEnabled = false;
    private boolean isDebugEnabled = false;
    private BuckEventBus eventBus = null;
    private Platform platform = null;

    private Builder() {}

    public ExecutionContext build() {
      return new ExecutionContext(
          projectFilesystem,
          console,
          androidPlatformTarget,
          targetDevice,
          defaultTestTimeoutMillis,
          isCodeCoverageEnabled,
          isJacocoEnabled,
          isDebugEnabled,
          eventBus,
          platform);
    }

    public Builder setExecutionContext(ExecutionContext executionContext) {
      setProjectFilesystem(executionContext.getProjectFilesystem());
      setConsole(executionContext.getConsole());
      setAndroidPlatformTarget(executionContext.getAndroidPlatformTargetOptional());
      setTargetDevice(executionContext.getTargetDeviceOptional());
      setDefaultTestTimeoutMillis(executionContext.getDefaultTestTimeoutMillis());
      setCodeCoverageEnabled(executionContext.isCodeCoverageEnabled());
      setJacocoEnabled(executionContext.isJacocoEnabled());
      setDebugEnabled(executionContext.isDebugEnabled());
      setEventBus(executionContext.getBuckEventBus());
      setPlatform(executionContext.getPlatform());
      return this;
    }

    public Builder setProjectFilesystem(ProjectFilesystem projectFilesystem) {
      this.projectFilesystem = Preconditions.checkNotNull(projectFilesystem);
      return this;
    }

    public Builder setConsole(Console console) {
      this.console = Preconditions.checkNotNull(console);
      return this;
    }

    public Builder setAndroidPlatformTarget(Optional<AndroidPlatformTarget> androidPlatformTarget) {
      this.androidPlatformTarget = Preconditions.checkNotNull(androidPlatformTarget);
      return this;
    }

    public Builder setTargetDevice(Optional<TargetDevice> targetDevice) {
      this.targetDevice = Preconditions.checkNotNull(targetDevice);
      return this;
    }

    /** Specify 0 for no timeout. */
    public Builder setDefaultTestTimeoutMillis(long defaultTestTimeoutMillis) {
      Preconditions.checkArgument(defaultTestTimeoutMillis >= 0,
          "Default timeout cannot be negative.");
      this.defaultTestTimeoutMillis = defaultTestTimeoutMillis;
      return this;
    }

    public Builder setCodeCoverageEnabled(boolean isCodeCoverageEnabled) {
      this.isCodeCoverageEnabled = isCodeCoverageEnabled;
      return this;
    }

    public Builder setJacocoEnabled(boolean isJacocoEnabled) {
      this.isJacocoEnabled = isJacocoEnabled;
      return this;
    }

    public Builder setDebugEnabled(boolean isDebugEnabled) {
      this.isDebugEnabled = isDebugEnabled;
      return this;
    }

    public Builder setEventBus(BuckEventBus eventBus) {
      this.eventBus = Preconditions.checkNotNull(eventBus);
      return this;
    }

    public Builder setPlatform(Platform platform) {
      this.platform = Preconditions.checkNotNull(platform);
      return this;
    }
  }
}
