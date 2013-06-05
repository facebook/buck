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
  public final boolean isCodeCoverageEnabled;
  private final boolean isDebugEnabled;
  private final ProcessExecutor processExecutor;

  public ExecutionContext(
      Verbosity verbosity,
      ProjectFilesystem projectFilesystem,
      Console console,
      Optional<AndroidPlatformTarget> androidPlatformTarget,
      Optional<File> ndkRoot,
      boolean isCodeCoverageEnabled,
      boolean isDebugEnabled) {
    this.verbosity = Preconditions.checkNotNull(verbosity);
    this.projectFilesystem = Preconditions.checkNotNull(projectFilesystem);
    this.console = Preconditions.checkNotNull(console);
    this.androidPlatformTarget = Preconditions.checkNotNull(androidPlatformTarget);
    this.ndkRoot = Preconditions.checkNotNull(ndkRoot);
    this.isCodeCoverageEnabled = isCodeCoverageEnabled;
    this.isDebugEnabled = isDebugEnabled;
    this.processExecutor = new ProcessExecutor(console);
  }

  /**
   * @return A clone of this {@link ExecutionContext} with {@code stdout} and {@code stderr}
   *    redirected to the provided {@link PrintStream}s.
   */
  public ExecutionContext createSubContext(PrintStream newStdout, PrintStream newStderr) {
    return new ExecutionContext(verbosity,
        this.projectFilesystem,
        new Console(newStdout, newStderr, console.getAnsi()),
        this.androidPlatformTarget,
        this.ndkRoot,
        this.isCodeCoverageEnabled,
        this.isDebugEnabled);
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

  public boolean isDebugEnabled() {
    return isDebugEnabled;
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

  public String getPathToAdbExecutable() throws NoAndroidSdkException {
    return getAndroidPlatformTarget().getAdbExecutable().getAbsolutePath();
  }

  public ProcessExecutor getProcessExecutor() {
    return processExecutor;
  }
}
