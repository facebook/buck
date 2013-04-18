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

import com.facebook.buck.shell.CommandRunner;
import com.facebook.buck.util.AndroidPlatformTarget;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.NoAndroidSdkException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.eventbus.EventBus;

import java.io.File;
import java.util.List;
import java.util.concurrent.Executor;

import javax.annotation.Nullable;

public class BuildContext {

  public final File projectRoot;
  private final DependencyGraph dependencyGraph;
  private final CommandRunner commandRunner;
  private final ProjectFilesystem projectFilesystem;
  private final JavaPackageFinder javaPackageFinder;
  private final EventBus events;
  private final Supplier<String> androidBootclasspathSupplier;

  private BuildContext(
      File projectRoot,
      DependencyGraph dependencyGraph,
      CommandRunner commandRunner,
      ProjectFilesystem projectFilesystem,
      JavaPackageFinder javaPackageFinder,
      EventBus events,
      Supplier<String> androidBootclasspathSupplier) {
    this.projectRoot = Preconditions.checkNotNull(projectRoot);
    this.dependencyGraph = Preconditions.checkNotNull(dependencyGraph);
    this.commandRunner = Preconditions.checkNotNull(commandRunner);
    this.projectFilesystem = Preconditions.checkNotNull(projectFilesystem);
    this.javaPackageFinder = Preconditions.checkNotNull(javaPackageFinder);
    this.events = Preconditions.checkNotNull(events);
    this.androidBootclasspathSupplier = Preconditions.checkNotNull(androidBootclasspathSupplier);
  }

  public CommandRunner getCommandRunner() {
    return commandRunner;
  }

  public DependencyGraph getDependencyGraph() {
    return dependencyGraph;
  }

  public Executor getExecutor() {
    return commandRunner.getListeningExecutorService();
  }

  public JavaPackageFinder getJavaPackageFinder() {
    return javaPackageFinder;
  }

  public static Builder builder() {
    return new Builder();
  }

  public ProjectFilesystem getProjectFilesystem() {
    return projectFilesystem;
  }

  public EventBus getEventBus() {
    return events;
  }

  public Supplier<String> getAndroidBootclasspathSupplier() {
    return androidBootclasspathSupplier;
  }

  public static class Builder {

    private File projectRoot = null;
    private DependencyGraph dependencyGraph = null;
    private CommandRunner commandRunner = null;
    private ProjectFilesystem projectFilesystem = null;
    private JavaPackageFinder javaPackgeFinder = null;
    private EventBus events = new EventBus();
    private Supplier<String> androidBootclasspathSupplier = null;

    private Builder() {}

    public BuildContext build() {
      if (androidBootclasspathSupplier == null) {
        setDefaultAndroidBootclasspathSupplier();
      }
      return new BuildContext(
          projectRoot,
          dependencyGraph,
          commandRunner,
          projectFilesystem,
          javaPackgeFinder,
          events,
          androidBootclasspathSupplier);
    }

    public Builder setProjectRoot(File projectRoot) {
      this.projectRoot = projectRoot;
      return this;
    }

    public Builder setDependencyGraph(DependencyGraph dependencyGraph) {
      this.dependencyGraph = dependencyGraph;
      return this;
    }

    public Builder setCommandRunner(CommandRunner commandRunner) {
      this.commandRunner = commandRunner;
      return this;
    }

    public Builder setProjectFilesystem(ProjectFilesystem fileystemProject) {
      this.projectFilesystem = fileystemProject;
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
          throw new HumanReadableException(new NoAndroidSdkException());
        }
      };
    }
  }
}
