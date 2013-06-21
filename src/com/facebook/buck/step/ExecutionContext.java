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
import com.facebook.buck.util.AndroidPlatformTarget;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.Verbosity;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;

import java.io.File;
import java.io.PrintStream;

public class ExecutionContext {

  private final Verbosity verbosity;
  private final ProjectFilesystem projectFilesystem;
  private final Console console;
  private final Optional<AndroidPlatformTarget> androidPlatformTarget;
  private final Optional<File> ndkRoot;
  private final long defaultTestTimeoutMillis;
  private final boolean isCodeCoverageEnabled;
  private final boolean isDebugEnabled;
  private final ProcessExecutor processExecutor;

  private ExecutionContext(
      ProjectFilesystem projectFilesystem,
      Console console,
      Optional<AndroidPlatformTarget> androidPlatformTarget,
      Optional<File> ndkRoot,
      long defaultTestTimeoutMillis,
      boolean isCodeCoverageEnabled,
      boolean isDebugEnabled) {
    this.verbosity = Preconditions.checkNotNull(console).getVerbosity();
    this.projectFilesystem = Preconditions.checkNotNull(projectFilesystem);
    this.console = Preconditions.checkNotNull(console);
    this.androidPlatformTarget = Preconditions.checkNotNull(androidPlatformTarget);
    this.ndkRoot = Preconditions.checkNotNull(ndkRoot);
    this.defaultTestTimeoutMillis = defaultTestTimeoutMillis;
    this.isCodeCoverageEnabled = isCodeCoverageEnabled;
    this.isDebugEnabled = isDebugEnabled;
    this.processExecutor = new ProcessExecutor(console);
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
        getNdkRoot(),
        getDefaultTestTimeoutMillis(),
        isCodeCoverageEnabled(),
        isDebugEnabled);
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

  public Optional<File> getNdkRoot() {
    return ndkRoot;
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
    return getAndroidPlatformTarget().getAdbExecutable().getAbsolutePath();
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
    private Optional<File> ndkRoot = Optional.absent();
    private long defaultTestTimeoutMillis = 0L;
    private boolean isCodeCoverageEnabled = false;
    private boolean isDebugEnabled = false;

    private Builder() {}

    public ExecutionContext build() {
      return new ExecutionContext(
          projectFilesystem,
          console,
          androidPlatformTarget,
          ndkRoot,
          defaultTestTimeoutMillis,
          isCodeCoverageEnabled,
          isDebugEnabled);
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

    public Builder setNdkRoot(Optional<File> ndkRoot) {
      this.ndkRoot = Preconditions.checkNotNull(ndkRoot);
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

    public Builder setDebugEnabled(boolean isDebugEnabled) {
      this.isDebugEnabled = isDebugEnabled;
      return this;
    }
  }
}
