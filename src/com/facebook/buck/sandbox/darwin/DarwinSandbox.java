/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.sandbox.darwin;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.sandbox.SandboxProperties;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;

/** Provides an interface to OS X native sandbox capabilities. */
public class DarwinSandbox {

  private static final String SANDBOX_EXEC = "/usr/bin/sandbox-exec";

  private static final ImmutableList<String> systemWriteablePaths =
      ImmutableList.of(
          "/dev",
          "/private/tmp",
          "/private/var/folders",
          "/private/var/tmp",
          "/tmp",
          "/var/folders");

  private final SandboxProperties sandboxProperties;
  private @Nullable Path sandboxProfilePath;

  public DarwinSandbox(SandboxProperties sandboxProperties) {
    this.sandboxProperties = sandboxProperties;
  }

  /** Creates a list of CL arguments to run a sandbox using the provided path to a profile */
  private static ImmutableList<String> createCommandLineArguments(String profilePath) {
    return ImmutableList.<String>builder().add(SANDBOX_EXEC).add("-f").add(profilePath).build();
  }

  /** Creates a list of CL arguments to run a sandbox to verify that sandbox is functioning */
  public static ImmutableList<String> createVerificationCommandLineArguments() {
    return ImmutableList.<String>builder()
        .add(SANDBOX_EXEC)
        .add("-p")
        .add("(version 1) (allow default)")
        .add("/usr/bin/true")
        .build();
  }

  /** Creates a list CL arguments that should be used to run a process in this sandbox */
  public ImmutableList<String> createCommandLineArguments() {
    return createCommandLineArguments(
        Objects.requireNonNull(sandboxProfilePath).toAbsolutePath().toString());
  }

  /** Creates a list CL arguments that should be used to run a process in this sandbox */
  public ImmutableList<String> createCommandLineArgumentsForDescription() {
    return createCommandLineArguments("sandbox-profile");
  }

  /** Performs initialization required by a sandbox */
  public void init(
      ProjectFilesystem projectFilesystem, SandboxProperties additionalSandboxProperties)
      throws IOException {
    sandboxProfilePath =
        projectFilesystem.resolve(projectFilesystem.createTempFile("sandbox-profile-", ".sb"));
    createProfileFileFromConfiguration(
        Objects.requireNonNull(sandboxProfilePath),
        SandboxProperties.builder()
            .from(sandboxProperties)
            .from(additionalSandboxProperties)
            .build());
  }

  /** Converts sandbox to Darwin sandbox profile and writes it to a file. */
  private static void createProfileFileFromConfiguration(
      Path sandboxProfilePath, SandboxProperties sandboxConfiguration) throws IOException {
    Collection<String> profileContent = generateSandboxProfileContent(sandboxConfiguration);
    Files.write(sandboxProfilePath, profileContent);
  }

  @VisibleForTesting
  static Collection<String> generateSandboxProfileContent(SandboxProperties sandboxProperties) {
    List<String> profileContent = new ArrayList<>();

    profileContent.add("(version 1)");
    profileContent.add("(allow default)");
    profileContent.add("(debug deny)");

    addProfileSection("deny file-read*", sandboxProperties.getDeniedToReadPaths(), profileContent);
    addProfileSection(
        "allow file-read-metadata",
        sandboxProperties.getAllowedToReadMetadataPaths(),
        profileContent);
    addProfileSection(
        "allow file-read*", sandboxProperties.getAllowedToReadPaths(), profileContent);

    profileContent.add("(deny file-write*)");

    List<String> allowedToWritePaths =
        Lists.newArrayList(sandboxProperties.getAllowedToWritePaths());
    allowedToWritePaths.addAll(systemWriteablePaths);
    addProfileSection("allow file-write*", allowedToWritePaths, profileContent);

    return profileContent;
  }

  private static void addProfileSection(
      String header, Collection<String> paths, List<String> profileContent) {
    profileContent.add("(" + header);
    paths.forEach(path -> profileContent.add(String.format("  (subpath \"%s\")", path)));
    profileContent.add(")");
  }
}
