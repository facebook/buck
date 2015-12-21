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

import com.facebook.buck.android.AndroidPlatformTarget;
import com.facebook.buck.android.NoAndroidSdkException;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ThrowableConsoleEvent;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.ClassLoaderCache;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.concurrent.ConcurrencyLimit;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.immutables.DeprecatedBuckStyleImmutable;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableMap;

import org.immutables.value.Value;

import java.io.Closeable;
import java.io.IOException;
import java.io.PrintStream;

import javax.annotation.Nullable;

@Value.Immutable(builder = false)
@DeprecatedBuckStyleImmutable
@SuppressWarnings("deprecation")
public abstract class ExecutionContext implements Closeable {

  @Value.Parameter
  public abstract Console getConsole();

  /**
   * Returns an {@link AndroidPlatformTarget} if the user specified one via {@code local.properties}
   * or some other mechanism. If the user failed to specify one, an exception will be thrown.
   */
  @Value.Parameter
  public abstract Supplier<AndroidPlatformTarget> getAndroidPlatformTargetSupplier();

  @Value.Parameter
  public abstract Optional<TargetDevice> getTargetDeviceOptional();

  @Value.Parameter
  public abstract long getDefaultTestTimeoutMillis();

  @Value.Parameter
  public abstract boolean isCodeCoverageEnabled();

  @Value.Parameter
  public abstract boolean isDebugEnabled();

  @Value.Parameter
  public abstract boolean shouldReportAbsolutePaths();

  @Value.Parameter
  public abstract ProcessExecutor getProcessExecutor();

  @Value.Parameter
  public abstract BuckEventBus getBuckEventBus();

  @Value.Parameter
  public abstract Platform getPlatform();

  @Value.Parameter
  public abstract ImmutableMap<String, String> getEnvironment();

  @Value.Parameter
  public abstract JavaPackageFinder getJavaPackageFinder();

  @Value.Parameter
  public abstract ObjectMapper getObjectMapper();

  @Value.Parameter
  public abstract ClassLoaderCache getClassLoaderCache();

  @Value.Parameter
  public abstract ConcurrencyLimit getConcurrencyLimit();

  @Value.Parameter
  public abstract Optional<AdbOptions> getAdbOptions();

  @Value.Parameter
  public abstract Optional<TargetDeviceOptions> getTargetDeviceOptions();


  @Value.Derived
  public Verbosity getVerbosity() {
    return getConsole().getVerbosity();
  }

  /**
   * @return A clone of this {@link ExecutionContext} with {@code stdout} and {@code stderr}
   *    redirected to the provided {@link PrintStream}s.
   */
  public ExecutionContext createSubContext(PrintStream newStdout, PrintStream newStderr) {
    Console console = new Console(
        this.getConsole().getVerbosity(),
        newStdout,
        newStderr,
        this.getConsole().getAnsi());

    return ImmutableExecutionContext.copyOf(this)
        .withConsole(console)
        .withProcessExecutor(new ProcessExecutor(console))
        .withClassLoaderCache(getClassLoaderCache().addRef());
  }

  public void logError(Throwable error, String msg, Object... formatArgs) {
    getBuckEventBus().post(ThrowableConsoleEvent.create(error, msg, formatArgs));
  }

  public void postEvent(BuckEvent event) {
    getBuckEventBus().post(event);
  }

  public PrintStream getStdErr() {
    return getConsole().getStdErr();
  }

  public PrintStream getStdOut() {
    return getConsole().getStdOut();
  }

  public Ansi getAnsi() {
    return getConsole().getAnsi();
  }

  public String getPathToAdbExecutable() throws NoAndroidSdkException {
    return getAndroidPlatformTarget().getAdbExecutable().toString();
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * Returns the {@link AndroidPlatformTarget}, if present. If not, throws a
   * {@link NoAndroidSdkException}. Use this when your logic requires the user to specify the
   * location of an Android SDK. A user who is building a "pure Java" (i.e., not Android) project
   * using Buck should never have to exercise this code path.
   * <p>
   * If the location of an Android SDK is optional, then use
   * {@link #getAndroidPlatformTargetSupplier()}.
   * @throws NoAndroidSdkException if no AndroidPlatformTarget is available
   */
  public AndroidPlatformTarget getAndroidPlatformTarget() throws NoAndroidSdkException {
    return getAndroidPlatformTargetSupplier().get();
  }

  @Override
  public void close() throws IOException {
    getClassLoaderCache().close();
  }

  public BuildId getBuildId() {
    return getBuckEventBus().getBuildId();
  }

  public static class Builder {

    @Nullable private Console console = null;
    private Supplier<AndroidPlatformTarget> androidPlatformTarget =
        AndroidPlatformTarget.EXPLODING_ANDROID_PLATFORM_TARGET_SUPPLIER;
    private Optional<TargetDevice> targetDevice = Optional.absent();
    private long defaultTestTimeoutMillis = 0L;
    private boolean isCodeCoverageEnabled = false;
    private boolean isDebugEnabled = false;
    private boolean shouldReportAbsolutePaths = false;
    @Nullable private ProcessExecutor processExecutor;
    @Nullable private BuckEventBus eventBus = null;
    @Nullable private Platform platform = null;
    @Nullable private ImmutableMap<String, String> environment = null;
    @Nullable private JavaPackageFinder javaPackageFinder = null;
    @Nullable private ObjectMapper objectMapper = null;
    private ClassLoaderCache classLoaderCache = new ClassLoaderCache();
    private ConcurrencyLimit concurrencyLimit =
        new ConcurrencyLimit(
            /* threadLimit */ Runtime.getRuntime().availableProcessors(),
            /* loadLimit */ Double.POSITIVE_INFINITY);
    private Optional<AdbOptions> adbOptions = Optional.absent();
    private Optional<TargetDeviceOptions> targetDeviceOptions = Optional.absent();

    private Builder() {}

    public ExecutionContext build() {
      return ImmutableExecutionContext.of(
          Preconditions.checkNotNull(console),
          androidPlatformTarget,
          targetDevice,
          defaultTestTimeoutMillis,
          isCodeCoverageEnabled,
          isDebugEnabled,
          shouldReportAbsolutePaths,
          Preconditions.checkNotNull(processExecutor),
          Preconditions.checkNotNull(eventBus),
          Preconditions.checkNotNull(platform),
          Preconditions.checkNotNull(environment),
          Preconditions.checkNotNull(javaPackageFinder),
          Preconditions.checkNotNull(objectMapper),
          Preconditions.checkNotNull(classLoaderCache),
          Preconditions.checkNotNull(concurrencyLimit),
          adbOptions,
          targetDeviceOptions);
    }

    public Builder setExecutionContext(ExecutionContext executionContext) {
      setConsole(executionContext.getConsole());
      setAndroidPlatformTargetSupplier(executionContext.getAndroidPlatformTargetSupplier());
      setTargetDevice(executionContext.getTargetDeviceOptional());
      setDefaultTestTimeoutMillis(executionContext.getDefaultTestTimeoutMillis());
      setCodeCoverageEnabled(executionContext.isCodeCoverageEnabled());
      setDebugEnabled(executionContext.isDebugEnabled());
      setShouldReportAbsolutePaths(executionContext.shouldReportAbsolutePaths());
      setEventBus(executionContext.getBuckEventBus());
      setPlatform(executionContext.getPlatform());
      setEnvironment(executionContext.getEnvironment());
      setJavaPackageFinder(executionContext.getJavaPackageFinder());
      setObjectMapper(executionContext.getObjectMapper());
      setConcurrencyLimit(executionContext.getConcurrencyLimit());
      setAdbOptions(executionContext.getAdbOptions());
      setTargetDeviceOptions(executionContext.getTargetDeviceOptions());
      return this;
    }

    public Builder setConsole(Console console) {
      this.console = console;
      if (this.processExecutor == null) {
        this.processExecutor = new ProcessExecutor(console);
      }
      return this;
    }

    public Builder setAndroidPlatformTargetSupplier(
        Supplier<AndroidPlatformTarget> androidPlatformTarget) {
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

    public Builder setShouldReportAbsolutePaths(boolean shouldReportAbsolutePaths) {
      this.shouldReportAbsolutePaths = shouldReportAbsolutePaths;
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

    public Builder setClassLoaderCache(ClassLoaderCache classLoaderCache) {
      this.classLoaderCache = classLoaderCache;
      return this;
    }

    public Builder setConcurrencyLimit(ConcurrencyLimit concurrencyLimit) {
      this.concurrencyLimit = concurrencyLimit;
      return this;
    }

    public Builder setAdbOptions(Optional<AdbOptions> adbOptions) {
      this.adbOptions = adbOptions;
      return this;
    }

    public Builder setTargetDeviceOptions(Optional<TargetDeviceOptions> targetDeviceOptions) {
      this.targetDeviceOptions = targetDeviceOptions;
      return this;
    }
  }
}
