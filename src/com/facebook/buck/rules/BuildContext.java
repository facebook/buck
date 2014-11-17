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
import com.facebook.buck.util.AndroidPlatformTarget;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableMap;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import javax.annotation.Nullable;

public class BuildContext {

  private final ActionGraph actionGraph;
  private final StepRunner stepRunner;
  private final ProjectFilesystem projectFilesystem;
  private final Clock clock;
  private final ArtifactCache artifactCache;
  private final JavaPackageFinder javaPackageFinder;
  private final BuckEventBus events;
  private final Supplier<String> androidBootclasspathSupplier;
  private final BuildDependencies buildDependencies;
  private final BuildId buildId;
  private final ImmutableMap<String, String> environment;

  private BuildContext(
      @Nullable ActionGraph actionGraph,
      @Nullable StepRunner stepRunner,
      @Nullable ProjectFilesystem projectFilesystem,
      @Nullable Clock clock,
      @Nullable ArtifactCache artifactCache,
      @Nullable JavaPackageFinder javaPackageFinder,
      @Nullable BuckEventBus events,
      @Nullable Supplier<String> androidBootclasspathSupplier,
      @Nullable BuildDependencies buildDependencies,
      @Nullable BuildId buildId,
      @Nullable ImmutableMap<String, String> environment) {

    this.actionGraph = Preconditions.checkNotNull(actionGraph);
    this.stepRunner = Preconditions.checkNotNull(stepRunner);
    this.projectFilesystem = Preconditions.checkNotNull(projectFilesystem);
    this.clock = Preconditions.checkNotNull(clock);
    this.artifactCache = Preconditions.checkNotNull(artifactCache);
    this.javaPackageFinder = Preconditions.checkNotNull(javaPackageFinder);
    this.events = Preconditions.checkNotNull(events);
    this.androidBootclasspathSupplier = Preconditions.checkNotNull(androidBootclasspathSupplier);
    this.buildDependencies = Preconditions.checkNotNull(buildDependencies);
    this.buildId = Preconditions.checkNotNull(buildId);
    this.environment = Preconditions.checkNotNull(environment);
  }

  public Path getProjectRoot() {
    return getProjectFilesystem().getRootPath();
  }

  public StepRunner getStepRunner() {
    return stepRunner;
  }

  public ActionGraph getActionGraph() {
    return actionGraph;
  }

  public JavaPackageFinder getJavaPackageFinder() {
    return javaPackageFinder;
  }

  /**
   * By design, there is no getter for {@link ProjectFilesystem}. At the point where a
   * {@link BuildRule} is using a {@link BuildContext} to generate its
   * {@link com.facebook.buck.step.Step}s, it should not be doing any I/O on local disk. Any reads
   * should be mediated through {@link OnDiskBuildInfo}, and {@link BuildInfoRecorder} will take
   * care of writes after the fact. The {@link BuildRule} should be working with relative file paths
   * so that builds can ultimately be distributed.
   * <p>
   * The primary reason this method exists is so that someone who blindly tries to add such a getter
   * will encounter a compilation error and will [hopefully] discover this comment.
   */
  private ProjectFilesystem getProjectFilesystem() {
    return projectFilesystem;
  }

  public ArtifactCache getArtifactCache() {
    return artifactCache;
  }

  public BuckEventBus getEventBus() {
    return events;
  }

  public Supplier<String> getAndroidBootclasspathSupplier() {
    return androidBootclasspathSupplier;
  }

  public BuildDependencies getBuildDependencies() {
    return buildDependencies;
  }


  /**
   * Creates an {@link OnDiskBuildInfo}.
   * <p>
   * This method should be visible to {@link AbstractBuildRule}, but not {@link BuildRule}s
   * in general.
   */
  OnDiskBuildInfo createOnDiskBuildInfoFor(BuildTarget target) {
    return new DefaultOnDiskBuildInfo(target, projectFilesystem);
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
        projectFilesystem,
        clock,
        buildId,
        environment,
        ruleKey,
        ruleKeyWithoutDeps);
  }

  public void logBuildInfo(String format, Object... args) {
    events.post(ConsoleEvent.fine(format, args));
  }

  public void logError(Throwable error, String msg, Object... formatArgs) {
    events.post(ThrowableConsoleEvent.create(error, msg, formatArgs));
  }

  public void logError(String msg, Object... formatArgs) {
    events.post(ConsoleEvent.severe(msg, formatArgs));
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {

    @Nullable
    private ActionGraph actionGraph = null;
    @Nullable
    private StepRunner stepRunner = null;
    @Nullable
    private ProjectFilesystem projectFilesystem = null;
    @Nullable
    private Clock clock = null;
    @Nullable
    private ArtifactCache artifactCache = null;
    @Nullable
    private JavaPackageFinder javaPackgeFinder = null;
    @Nullable
    private BuckEventBus events = null;
    @Nullable
    private Supplier<String> androidBootclasspathSupplier = null;
    private BuildDependencies buildDependencies = BuildDependencies.getDefault();
    @Nullable
    private BuildId buildId = null;
    private ImmutableMap<String, String> environment = ImmutableMap.of();

    private Builder() {}

    public BuildContext build() {
      if (androidBootclasspathSupplier == null) {
        setDefaultAndroidBootclasspathSupplier();
      }

      return new BuildContext(
          actionGraph,
          stepRunner,
          projectFilesystem,
          clock,
          artifactCache,
          javaPackgeFinder,
          events,
          androidBootclasspathSupplier,
          buildDependencies,
          buildId,
          environment);
    }

    public Builder setActionGraph(ActionGraph actionGraph) {
      this.actionGraph = actionGraph;
      return this;
    }

    public Builder setStepRunner(StepRunner stepRunner) {
      this.stepRunner = stepRunner;
      return this;
    }

    public Builder setProjectFilesystem(ProjectFilesystem fileystemProject) {
      this.projectFilesystem = fileystemProject;
      return this;
    }

    public Builder setClock(Clock clock) {
      this.clock = clock;
      return this;
    }

    public Builder setArtifactCache(ArtifactCache artifactCache) {
      this.artifactCache = artifactCache;
      return this;
    }

    public Builder setJavaPackageFinder(JavaPackageFinder javaPackgeFinder) {
      this.javaPackgeFinder = javaPackgeFinder;
      return this;
    }

    public Builder setEventBus(BuckEventBus events) {
      this.events = events;
      return this;
    }

    public Builder setBuildDependencies(BuildDependencies buildDependencies) {
      this.buildDependencies = buildDependencies;
      return this;
    }

    public Builder setAndroidBootclasspathForAndroidPlatformTarget(
        Optional<AndroidPlatformTarget> maybeAndroidPlatformTarget) {
      if (maybeAndroidPlatformTarget.isPresent()) {
        final AndroidPlatformTarget androidPlatformTarget = maybeAndroidPlatformTarget.get();
        this.androidBootclasspathSupplier = Suppliers.memoize(new Supplier<String>() {
          @Override
          @Nullable
          public String get() {
            List<Path> bootclasspathEntries = androidPlatformTarget.getBootclasspathEntries();
            Preconditions.checkState(!bootclasspathEntries.isEmpty(),
                "There should be entries for the bootclasspath");
            return Joiner.on(File.pathSeparator).join(bootclasspathEntries);
          }
        });
      } else {
        setDefaultAndroidBootclasspathSupplier();
      }
      return this;
    }

    public Builder setBuildId(BuildId buildId) {
      this.buildId = buildId;
      return this;
    }

    public Builder setEnvironment(ImmutableMap<String, String> environment) {
      this.environment = environment;
      return this;
    }

    private void setDefaultAndroidBootclasspathSupplier() {
      // Will throw an exception only if the Android bootclasspath is requested.
      this.androidBootclasspathSupplier = new Supplier<String>() {
        @Override
        public String get() {
          throw new NoAndroidSdkException();
        }
      };
    }
  }
}
