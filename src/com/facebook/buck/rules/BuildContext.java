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

package com.facebook.buck.rules;

import com.facebook.buck.android.AndroidPlatformTarget;
import com.facebook.buck.android.NoAndroidSdkException;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.ThrowableConsoleEvent;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.java.JavaPackageFinder;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.step.StepRunner;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableMap;

import org.immutables.value.Value;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Value.Immutable
@BuckStyleImmutable
public abstract class BuildContext {

  private static final Supplier<String> DEFAULT_ANDROID_BOOTCLASSPATH_SUPPLIER =
      new Supplier<String>() {
        @Override
        public String get() {
          throw new NoAndroidSdkException();
        }
      };

  public abstract ActionGraph getActionGraph();
  public abstract StepRunner getStepRunner();

  /**
   * By design, there is no public getter for {@link ProjectFilesystem}. At the point where a
   * {@link BuildRule} is using a {@link BuildContext} to generate its
   * {@link com.facebook.buck.step.Step}s, it should not be doing any I/O on local disk. Any reads
   * should be mediated through {@link OnDiskBuildInfo}, and {@link BuildInfoRecorder} will take
   * care of writes after the fact. The {@link BuildRule} should be working with relative file paths
   * so that builds can ultimately be distributed.
   * <p>
   * The primary reason this method exists is so that someone who blindly tries to add such a getter
   * will encounter a compilation error and will [hopefully] discover this comment.
   */
  protected abstract ProjectFilesystem getProjectFilesystem();

  protected abstract Clock getClock();
  public abstract ArtifactCache getArtifactCache();
  public abstract JavaPackageFinder getJavaPackageFinder();
  public abstract BuckEventBus getEventBus();

  @Value.Default
  public Supplier<String> getAndroidBootclasspathSupplier() {
    return DEFAULT_ANDROID_BOOTCLASSPATH_SUPPLIER;
  }

  @Value.Default
  public BuildDependencies getBuildDependencies() {
    return BuildDependencies.getDefault();
  }

  protected abstract BuildId getBuildId();
  protected abstract Map<String, String> getEnvironment();

  public Path getProjectRoot() {
    return getProjectFilesystem().getRootPath();
  }

  /**
   * Creates an {@link OnDiskBuildInfo}.
   * <p>
   * This method should be visible to {@link AbstractBuildRule}, but not {@link BuildRule}s
   * in general.
   */
  OnDiskBuildInfo createOnDiskBuildInfoFor(BuildTarget target) {
    return new DefaultOnDiskBuildInfo(target, getProjectFilesystem());
  }

  /**
   * Creates an {@link BuildInfoRecorder}.
   * <p>
   * This method should be visible to {@link AbstractBuildRule}, but not {@link BuildRule}s
   * in general.
   */
  BuildInfoRecorder createBuildInfoRecorder(BuildTarget buildTarget,
      RuleKey ruleKey,
      RuleKey ruleKeyWithoutDeps) {
    return new BuildInfoRecorder(
        buildTarget,
        getProjectFilesystem(),
        getClock(),
        getBuildId(),
        ImmutableMap.copyOf(getEnvironment()),
        ruleKey,
        ruleKeyWithoutDeps);
  }

  public void logBuildInfo(String format, Object... args) {
    getEventBus().post(ConsoleEvent.fine(format, args));
  }

  public void logError(Throwable error, String msg, Object... formatArgs) {
    getEventBus().post(ThrowableConsoleEvent.create(error, msg, formatArgs));
  }

  public void logError(String msg, Object... formatArgs) {
    getEventBus().post(ConsoleEvent.severe(msg, formatArgs));
  }

  public static Supplier<String> getAndroidBootclasspathSupplierForAndroidPlatformTarget(
      Optional<AndroidPlatformTarget> maybeAndroidPlatformTarget
  ) {
    if (maybeAndroidPlatformTarget.isPresent()) {
      final AndroidPlatformTarget androidPlatformTarget = maybeAndroidPlatformTarget.get();
      return Suppliers.memoize(
          new Supplier<String>() {
            @Override
            public String get() {
              List<Path> bootclasspathEntries = androidPlatformTarget.getBootclasspathEntries();
              Preconditions.checkState(
                  !bootclasspathEntries.isEmpty(),
                  "There should be entries for the bootclasspath");
              return Joiner.on(File.pathSeparator).join(bootclasspathEntries);
            }
          });
    } else {
      return DEFAULT_ANDROID_BOOTCLASSPATH_SUPPLIER;
    }
  }
}
