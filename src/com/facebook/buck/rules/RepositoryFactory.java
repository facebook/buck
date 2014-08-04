/*
 * Copyright 2014-present Facebook, Inc.
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

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.java.JavaBuckConfig;
import com.facebook.buck.java.JavaCompilerEnvironment;
import com.facebook.buck.util.AndroidDirectoryResolver;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultAndroidDirectoryResolver;
import com.facebook.buck.util.DefaultPropertyFinder;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.PropertyFinder;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentMap;

/**
 * Holds all the global state needed to create {@link Repository} objects. These are created lazily
 * and cached.
 */
public class RepositoryFactory {

  private final ImmutableMap<String, String> clientEnvironment;
  private final Platform platform;
  private final Console console;

  private final ConcurrentMap<Path, Repository> cachedRepositories = Maps.newConcurrentMap();

  public RepositoryFactory(
      ImmutableMap<String, String> clientEnvironment,
      Platform platform,
      Console console) {
    this.clientEnvironment = Preconditions.checkNotNull(clientEnvironment);
    this.platform = Preconditions.checkNotNull(platform);
    this.console = Preconditions.checkNotNull(console);
  }

  public Repository getRepositoryByAbsolutePath(Path absolutePath)
      throws IOException, InterruptedException {
    Preconditions.checkNotNull(absolutePath);
    Preconditions.checkArgument(absolutePath.isAbsolute());
    // Resolve symlinks and everything, so that we can detect the same repo referenced from two
    // different (but ultimately equivalent) paths.
    Path root = absolutePath.toRealPath();

    if (cachedRepositories.containsKey(root)) {
      return cachedRepositories.get(root);
    }

    // Create common command parameters. projectFilesystem initialization looks odd because it needs
    // ignorePaths from a BuckConfig instance, which in turn needs a ProjectFilesystem (i.e. this
    // solves a bootstrapping issue).
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(
        root,
        BuckConfig.createDefaultBuckConfig(new ProjectFilesystem(root), platform, clientEnvironment)
            .getIgnorePaths());
    BuckConfig config = BuckConfig.createDefaultBuckConfig(
        projectFilesystem,
        platform,
        clientEnvironment);

    // Configure the AndroidDirectoryResolver.
    PropertyFinder propertyFinder = new DefaultPropertyFinder(
        projectFilesystem,
        clientEnvironment);
    AndroidDirectoryResolver androidDirectoryResolver =
        new DefaultAndroidDirectoryResolver(
            projectFilesystem,
            config.getNdkVersion(),
            propertyFinder);
    // Look up the javac version.
    JavaBuckConfig javaConfig = new JavaBuckConfig(config);
    ProcessExecutor processExecutor = new ProcessExecutor(console);
    JavaCompilerEnvironment javacEnv = javaConfig.getJavaCompilerEnvironment(processExecutor);

    // NOTE:  If any other variable is used when configuring buildRuleTypes, it MUST be passed down
    // to the Daemon and implement equals/hashCode so we can invalidate the Parser if values used
    // for configuring buildRuleTypes have changed between builds.
    KnownBuildRuleTypes buildRuleTypes =
        KnownBuildRuleTypes.createInstance(
            config,
            androidDirectoryResolver,
            javacEnv);

    Repository repository = new Repository(
        projectFilesystem,
        buildRuleTypes,
        config,
        androidDirectoryResolver);
    cachedRepositories.put(root, repository);

    return repository;
  }
}
