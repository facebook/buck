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

package com.facebook.buck.shell;

import com.facebook.buck.util.AndroidPlatformTarget;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.NoAndroidSdkException;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;

import java.io.File;
import java.io.PrintStream;

public class ExecutionContext {

  private final Verbosity verbosity;
  private final File projectDirectoryRoot;
  private final Optional<AndroidPlatformTarget> androidPlatformTarget;
  private final Optional<File> ndkRoot;
  private final Ansi ansi;
  public final boolean isCodeCoverageEnabled;
  private final boolean isDebugEnabled;
  private final PrintStream stdout;
  private final PrintStream stderr;
  private final ProcessExecutor processExecutor;

  public ExecutionContext(
      Verbosity verbosity,
      File directory,
      Optional<AndroidPlatformTarget> androidPlatformTarget,
      Optional<File> ndkRoot,
      Ansi ansi,
      boolean isCodeCoverageEnabled,
      boolean isDebugEnabled,
      PrintStream stdout,
      PrintStream stderr) {
    this.verbosity = Preconditions.checkNotNull(verbosity);
    this.projectDirectoryRoot = Preconditions.checkNotNull(directory);
    this.androidPlatformTarget = Preconditions.checkNotNull(androidPlatformTarget);
    this.ndkRoot = Preconditions.checkNotNull(ndkRoot);
    this.ansi = Preconditions.checkNotNull(ansi);
    this.isCodeCoverageEnabled = isCodeCoverageEnabled;
    this.isDebugEnabled = isDebugEnabled;
    this.stdout = Preconditions.checkNotNull(stdout);
    this.stderr = Preconditions.checkNotNull(stderr);
    this.processExecutor = new ProcessExecutor(stdout, stderr, ansi);
  }

  /**
   * @return A clone of this {@link ExecutionContext} with {@code stdout} and {@code stderr}
   *    redirected to the provided {@link PrintStream}s.
   */
  public ExecutionContext createSubContext(PrintStream newStdout, PrintStream newStderr) {
    return new ExecutionContext(verbosity,
        this.projectDirectoryRoot,
        this.androidPlatformTarget,
        this.ndkRoot,
        this.ansi,
        this.isCodeCoverageEnabled,
        this.isDebugEnabled,
        newStdout,
        newStderr);
  }

  public Verbosity getVerbosity() {
    return verbosity;
  }

  public File getProjectDirectoryRoot() {
    return projectDirectoryRoot;
  }

  public boolean isDebugEnabled() {
    return isDebugEnabled;
  }

  public PrintStream getStdErr() {
    return stderr;
  }

  public PrintStream getStdOut() {
    return stdout;
  }

  public Ansi getAnsi() {
    return ansi;
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
