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
import com.facebook.buck.step.StepRunner;
import com.facebook.buck.util.AndroidPlatformTarget;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.eventbus.EventBus;
import com.google.common.util.concurrent.ListeningExecutorService;

import java.io.File;
import java.util.List;

import javax.annotation.Nullable;

public class BuildContext {

  public final File projectRoot;
  private final DependencyGraph dependencyGraph;
  private final StepRunner stepRunner;
  private final ProjectFilesystem projectFilesystem;
  private final ArtifactCache artifactCache;
  private final JavaPackageFinder javaPackageFinder;
  private final EventBus events;
  private final Supplier<String> androidBootclasspathSupplier;
  private final BuildDependencies buildDependencies;

  @Nullable private final Console console;

  private BuildContext(
      File projectRoot,
      DependencyGraph dependencyGraph,
      StepRunner stepRunner,
      ProjectFilesystem projectFilesystem,
      ArtifactCache artifactCache,
      JavaPackageFinder javaPackageFinder,
      EventBus events,
      Supplier<String> androidBootclasspathSupplier,
      BuildDependencies buildDependencies,
      Console console) {
    this.projectRoot = Preconditions.checkNotNull(projectRoot);
    this.dependencyGraph = Preconditions.checkNotNull(dependencyGraph);
    this.stepRunner = Preconditions.checkNotNull(stepRunner);
    this.projectFilesystem = Preconditions.checkNotNull(projectFilesystem);
    this.artifactCache = Preconditions.checkNotNull(artifactCache);
    this.javaPackageFinder = Preconditions.checkNotNull(javaPackageFinder);
    this.events = Preconditions.checkNotNull(events);
    this.androidBootclasspathSupplier = Preconditions.checkNotNull(androidBootclasspathSupplier);
    this.buildDependencies = Preconditions.checkNotNull(buildDependencies);
    this.console = console;
  }

  public StepRunner getCommandRunner() {
    return stepRunner;
  }

  public DependencyGraph getDependencyGraph() {
    return dependencyGraph;
  }

  public ListeningExecutorService getExecutor() {
    return stepRunner.getListeningExecutorService();
  }

  public JavaPackageFinder getJavaPackageFinder() {
    return javaPackageFinder;
  }

  public ProjectFilesystem getProjectFilesystem() {
    return projectFilesystem;
  }

  public ArtifactCache getArtifactCache() {
    return artifactCache;
  }

  public EventBus getEventBus() {
    return events;
  }

  public Supplier<String> getAndroidBootclasspathSupplier() {
    return androidBootclasspathSupplier;
  }

  public BuildDependencies getBuildDependencies() {
    return buildDependencies;
  }

  public void logBuildInfo(String format, Object... args) {
    if (console != null && console.getVerbosity().shouldPrintCommand()) {
      console.getStdErr().printf(format + '\n', args);
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {

    private File projectRoot = null;
    private DependencyGraph dependencyGraph = null;
    private StepRunner stepRunner = null;
    private ProjectFilesystem projectFilesystem = null;
    private ArtifactCache artifactCache = null;
    private JavaPackageFinder javaPackgeFinder = null;
    private EventBus events = new EventBus();
    private Supplier<String> androidBootclasspathSupplier = null;
    private BuildDependencies buildDependencies = BuildDependencies.getDefault();
    private Console console = null;

    private Builder() {}

    public BuildContext build() {
      if (androidBootclasspathSupplier == null) {
        setDefaultAndroidBootclasspathSupplier();
      }
      return new BuildContext(
          projectRoot,
          dependencyGraph,
          stepRunner,
          projectFilesystem,
          artifactCache,
          javaPackgeFinder,
          events,
          androidBootclasspathSupplier,
          buildDependencies,
          console);
    }

    public Builder setProjectRoot(File projectRoot) {
      this.projectRoot = projectRoot;
      return this;
    }

    public Builder setDependencyGraph(DependencyGraph dependencyGraph) {
      this.dependencyGraph = dependencyGraph;
      return this;
    }

    public Builder setCommandRunner(StepRunner stepRunner) {
      this.stepRunner = stepRunner;
      return this;
    }

    public Builder setProjectFilesystem(ProjectFilesystem fileystemProject) {
      this.projectFilesystem = fileystemProject;
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

    public Builder setEventBus(EventBus events) {
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
            List<File> bootclasspathEntries = androidPlatformTarget.getBootclasspathEntries();
            Preconditions.checkState(!bootclasspathEntries.isEmpty(),
                "There should be entries for the bootclasspath");
            return Joiner.on(":").join(bootclasspathEntries);
          }
        });
      } else {
        setDefaultAndroidBootclasspathSupplier();
      }
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

    public Builder setConsole(Console console) {
      this.console = console;
      return this;
    }
  }
}
