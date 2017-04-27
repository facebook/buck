/*
 * Copyright 2013-present Facebook, Inc.
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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.util.ObjectMappers;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Optional;

/**
 * Utility for reading the metadata associated with a build rule's output. This is metadata that
 * would have been written by a {@link BuildInfoRecorder} when the rule was built initially.
 *
 * <p>Such metadata is stored as key/value pairs.
 */
public class DefaultOnDiskBuildInfo implements OnDiskBuildInfo {

  private static final Logger LOG = Logger.get(DefaultOnDiskBuildInfo.class);

  private final BuildTarget buildTarget;
  private final ProjectFilesystem projectFilesystem;
  private final BuildInfoStore buildInfoStore;
  private final Path metadataDirectory;

  public DefaultOnDiskBuildInfo(
      BuildTarget target, ProjectFilesystem projectFilesystem, BuildInfoStore buildInfoStore) {
    this.buildTarget = target;
    this.projectFilesystem = projectFilesystem;
    this.buildInfoStore = buildInfoStore;
    this.metadataDirectory = BuildInfo.getPathToMetadataDirectory(target, projectFilesystem);
  }

  @Override
  public Optional<String> getValue(String key) {
    return projectFilesystem.readFileIfItExists(metadataDirectory.resolve(key));
  }

  @Override
  public Optional<String> getBuildValue(String key) {
    return buildInfoStore.readMetadata(buildTarget, key);
  }

  @Override
  public Optional<ImmutableList<String>> getValues(String key) {
    Optional<String> value = getValue(key);
    if (!value.isPresent()) {
      return Optional.empty();
    }
    try {
      ImmutableList<String> list =
          ObjectMappers.readValue(value.get(), new TypeReference<ImmutableList<String>>() {});
      return Optional.of(list);
    } catch (IOException ignored) {
      return Optional.empty();
    }
  }

  @Override
  public ImmutableList<String> getValuesOrThrow(String key) {
    Optional<ImmutableList<String>> values = getValues(key);
    if (values.isPresent()) {
      return values.get();
    } else {
      Path path = projectFilesystem.getPathForRelativePath(metadataDirectory.resolve(key));
      try {
        BasicFileAttributes attr = Files.readAttributes(path, BasicFileAttributes.class);
        throw new RuntimeException(
            String.format(
                "Attributes of file "
                    + path
                    + " are :"
                    + "Size: %d, "
                    + "Is Directory: %b, "
                    + "Is regular file %b, "
                    + "Is symbolic link %b, "
                    + "Is other %b, "
                    + "Last access time %s, "
                    + "Last modify time %s, "
                    + "Creation time %s",
                attr.size(),
                attr.isDirectory(),
                attr.isRegularFile(),
                attr.isSymbolicLink(),
                attr.isOther(),
                attr.lastAccessTime(),
                attr.lastModifiedTime(),
                attr.creationTime()));
      } catch (IOException e) {
        throw new RuntimeException("Failed to read " + path, e);
      }
    }
  }

  @Override
  public Optional<ImmutableMap<String, String>> getBuildMap(String key) {
    Optional<String> value = getBuildValue(key);
    if (!value.isPresent()) {
      return Optional.empty();
    }
    try {
      ImmutableMap<String, String> map =
          ObjectMappers.readValue(
              value.get(), new TypeReference<ImmutableMap<String, String>>() {});
      return Optional.of(map);
    } catch (IOException ignored) {
      return Optional.empty();
    }
  }

  @Override
  public Optional<Sha1HashCode> getHash(String key) {
    Optional<String> optionalValue = getValue(key);
    if (optionalValue.isPresent()) {
      String value = optionalValue.get();
      try {
        return Optional.of(Sha1HashCode.of(value));
      } catch (IllegalArgumentException e) {
        LOG.error(e, "DefaultOnDiskBuildInfo.getHash(%s): Cannot transform %s to SHA1", key, value);
        return Optional.empty();
      }
    } else {
      LOG.warn("DefaultOnDiskBuildInfo.getHash(%s): Hash not found", key);
      return Optional.empty();
    }
  }

  @Override
  public Optional<RuleKey> getRuleKey(String key) {
    try {
      return getBuildValue(key).map(RuleKey::new);
    } catch (IllegalArgumentException ignored) {
      return Optional.empty();
    }
  }

  @Override
  public List<String> getOutputFileContentsByLine(Path pathRelativeToProjectRoot)
      throws IOException {
    return projectFilesystem.readLines(pathRelativeToProjectRoot);
  }

  @Override
  public void deleteExistingMetadata() throws IOException {
    buildInfoStore.deleteMetadata(buildTarget);
    projectFilesystem.deleteRecursivelyIfExists(metadataDirectory);
  }
}
