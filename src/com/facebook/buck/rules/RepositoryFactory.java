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
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.python.PythonBuckConfig;
import com.facebook.buck.python.PythonEnvironment;
import com.facebook.buck.android.AndroidDirectoryResolver;
import com.facebook.buck.util.Console;
import com.facebook.buck.android.DefaultAndroidDirectoryResolver;
import com.facebook.buck.util.DefaultPropertyFinder;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.PropertyFinder;
import com.facebook.buck.util.environment.Platform;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

/**
 * Holds all the global state needed to create {@link Repository} objects. These are created lazily
 * and cached.
 */
public class RepositoryFactory {

  private final ImmutableMap<String, String> clientEnvironment;
  private final Platform platform;
  private final Console console;

  @VisibleForTesting
  protected final ConcurrentMap<Path, Repository> cachedRepositories = Maps.newConcurrentMap();
  @VisibleForTesting
  protected final BiMap<Path, Optional<String>> canonicalPathNames =
      Maps.synchronizedBiMap(HashBiMap.<Path, Optional<String>>create());

  public RepositoryFactory(
      ImmutableMap<String, String> clientEnvironment,
      Platform platform,
      Console console,
      Path canonicalRootPath) {
    this.clientEnvironment = clientEnvironment;
    this.platform = platform;
    this.console = console;
    // Ideally we would do isRealPath() to make sure canonicalRootPath doesn't contain symlinks, but
    // since this requires IO it's the responsibility of the caller. Checking isAbsolute() is the
    // next best thing.
    Preconditions.checkArgument(canonicalRootPath.isAbsolute());
    // The root repo has no explicit name. All other repos will get a global, unique name.
    this.canonicalPathNames.put(canonicalRootPath, Optional.<String>absent());
  }

  /**
   * The table of canonical names grows as more repos are discovered. This returns a copy of the
   * current table.
   */
  public ImmutableMap<Path, Optional<String>> getCanonicalPathNames() {
    return ImmutableMap.copyOf(canonicalPathNames);
  }

  public Repository getRepositoryByCanonicalName(Optional<String> canonicalName)
      throws IOException, InterruptedException {
    if (!canonicalPathNames.containsValue(canonicalName)) {
      throw new HumanReadableException("No repository with canonical name '%s'.", canonicalName);
    }
    Path repoPath = canonicalPathNames.inverse().get(canonicalName);
    return getRepositoryByAbsolutePath(repoPath);
  }

  public Repository getRootRepository()
      throws IOException, InterruptedException {
    return getRepositoryByCanonicalName(Optional.<String>absent());
  }

  public Repository getRepositoryByAbsolutePath(Path absolutePath)
      throws IOException, InterruptedException {
    Preconditions.checkArgument(absolutePath.isAbsolute());

    if (cachedRepositories.containsKey(absolutePath)) {
      return cachedRepositories.get(absolutePath);
    }

    if (!canonicalPathNames.containsKey(absolutePath)) {
      throw new HumanReadableException("No repository name known for " + absolutePath);
    }
    Optional<String> name = canonicalPathNames.get(absolutePath);

    // Create common command parameters. projectFilesystem initialization looks odd because it needs
    // ignorePaths from a BuckConfig instance, which in turn needs a ProjectFilesystem (i.e. this
    // solves a bootstrapping issue).
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(
        absolutePath,
        BuckConfig.createDefaultBuckConfig(
            new ProjectFilesystem(absolutePath),
            platform,
            clientEnvironment).getIgnorePaths());
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
    ProcessExecutor processExecutor = new ProcessExecutor(console);

    PythonBuckConfig pythonConfig = new PythonBuckConfig(config);
    PythonEnvironment pythonEnv = pythonConfig.getPythonEnvironment(processExecutor);

    // NOTE:  If any other variable is used when configuring buildRuleTypes, it MUST be passed down
    // to the Daemon and implement equals/hashCode so we can invalidate the Parser if values used
    // for configuring buildRuleTypes have changed between builds.
    KnownBuildRuleTypes buildRuleTypes =
        KnownBuildRuleTypes.createInstance(
            config,
            projectFilesystem,
            processExecutor,
            androidDirectoryResolver,
            pythonEnv);

    Repository repository = ImmutableRepository.of(
        name,
        projectFilesystem,
        buildRuleTypes,
        config,
        this,
        androidDirectoryResolver);
    cachedRepositories.put(absolutePath, repository);

    updateCanonicalNames(repository.getBuckConfig().getRepositoryPaths());

    return repository;
  }

  /**
   * BuildTargets from the same repo need to always use the same repo name, no matter where the
   * target is being used, or else our build will get very confused.  But that's not how build
   * target syntax works in actual BUCK files. There, repo names are interpreted relative to the
   * local .buckconfig, and targets within the repo aren't given any explicit repo name at all. We
   * need to figure out a target's canonical repo name when we parse it, and in order to do that, we
   * maintain the table of canonical names here as repos are discovered. Whenever a Repository is
   * created, we check to see if it defines any external repos we haven't seen before, and if so we
   * add their names to the canonical table.
   * @param externalRepositoryPaths
   */
  private synchronized void updateCanonicalNames(Map<String, Path> externalRepositoryPaths) {
    for (String possibleName : externalRepositoryPaths.keySet()) {
      Path canonicalPath = externalRepositoryPaths.get(possibleName);
      // If we already have a name for this repo, skip it.
      if (canonicalPathNames.containsKey(canonicalPath)) {
        // TODO(jacko): Should we choke in this case, if possibleName is different from what we
        // already have (excluding Optional.absent() for the root repo).
        continue;
      }
      // Make sure the new name hasn't been used before at a different path.
      // TODO(jacko): Maybe generate a new unique name when there's a collision?
      if (canonicalPathNames.containsValue(Optional.of(possibleName))) {
        Path knownPath = canonicalPathNames.inverse().get(Optional.of(possibleName));
        throw new HumanReadableException(
            String.format(
                "Tried to define repo \"%s\" at path %s, but it's already defined at path %s",
                possibleName,
                canonicalPath,
                knownPath));
      }
      // Update the canonical table with the new name.
      canonicalPathNames.put(canonicalPath, Optional.of(possibleName));
    }
  }
}
