/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.core.build.engine.buildinfo;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.hashing.FileHashLoader;
import com.facebook.buck.util.json.ObjectMappers;
import com.facebook.buck.util.stream.RichStream;
import com.facebook.buck.util.types.Either;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;

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
  private final Path artifactMetadataFilePath;

  public DefaultOnDiskBuildInfo(
      BuildTarget target, ProjectFilesystem projectFilesystem, BuildInfoStore buildInfoStore) {
    this.buildTarget = target;
    this.projectFilesystem = projectFilesystem;
    this.buildInfoStore = buildInfoStore;
    this.metadataDirectory =
        BuildInfo.getPathToArtifactMetadataDirectory(target, projectFilesystem);
    this.artifactMetadataFilePath =
        BuildInfo.getPathToArtifactMetadataFile(target, projectFilesystem);
  }

  @Override
  public Either<String, Exception> getValue(String key) {
    if (key.equals(BuildInfo.MetadataKey.DEP_FILE)) {
      Path depFilePath = metadataDirectory.resolve(key);
      return projectFilesystem
          .readFileIfItExists(depFilePath)
          .map(Either::<String, Exception>ofLeft)
          .orElseGet(
              () -> {
                return Either.ofRight(new Exception("depfile does not exist: " + depFilePath));
              });
    }

    Optional<String> artifactMetadata =
        projectFilesystem.readFileIfItExists(artifactMetadataFilePath);

    if (!artifactMetadata.isPresent()) {
      return Either.ofRight(
          new Exception("artifact metadata file does not exist: " + artifactMetadataFilePath));
    }

    try {
      ImmutableMap<String, String> json =
          ObjectMappers.readValue(
              artifactMetadata.get(), new TypeReference<ImmutableMap<String, String>>() {});
      return Optional.ofNullable(json.get(key))
          .map(Either::<String, Exception>ofLeft)
          .orElseGet(
              () -> {
                return Either.ofRight(
                    new Exception(
                        "artifact metadata file "
                            + artifactMetadataFilePath
                            + " does not have a key "
                            + key));
              });
    } catch (IOException e) {
      LOG.warn(
          e,
          "Failed to get %s from file %s with metadata %s.",
          key,
          artifactMetadataFilePath.toString(),
          artifactMetadata.get());
      return Either.ofRight(
          new Exception("failed to read artifact metadata file " + artifactMetadataFilePath, e));
    }
  }

  @Override
  public Optional<String> getBuildValue(String key) {
    return buildInfoStore.readMetadata(buildTarget, key);
  }

  @Override
  public Optional<ImmutableList<String>> getValues(String key) {
    try {
      return Optional.of(getValuesOrThrow(key));
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  @Override
  public ImmutableList<String> getValuesOrThrow(String key) throws IOException {
    Either<String, Exception> value = getValue(key);
    if (!value.isLeft()) {
      throw new FileNotFoundException(metadataDirectory.resolve(key).toString());
    }
    try {
      return ObjectMappers.readValue(
          value.getLeft(), new TypeReference<ImmutableList<String>>() {});
    } catch (IOException ignored) {
      Path path = projectFilesystem.getPathForRelativePath(metadataDirectory.resolve(key));
      BasicFileAttributes attr = Files.readAttributes(path, BasicFileAttributes.class);
      throw new IOException(
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
    }
  }

  @Override
  public Optional<ImmutableMap<String, String>> getMap(String key) {
    Either<String, Exception> value = getValue(key);
    if (!value.isLeft()) {
      return Optional.empty();
    }
    try {
      ImmutableMap<String, String> map =
          ObjectMappers.readValue(
              value.getLeft(), new TypeReference<ImmutableMap<String, String>>() {});
      return Optional.of(map);
    } catch (IOException ignored) {
      return Optional.empty();
    }
  }

  @Override
  public ImmutableSortedSet<Path> getOutputPaths() {
    // Recorded paths always use unix file separators, convert this to the appropriate separators
    return RichStream.from(getValues(BuildInfo.MetadataKey.RECORDED_PATHS).get())
        .map(path -> path.replace("/", File.separator))
        .map(Paths::get)
        .concat(RichStream.of(metadataDirectory))
        .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
  }

  @Override
  public ImmutableSortedSet<Path> getPathsForArtifact() throws IOException {
    return getRecursivePaths(getOutputPaths());
  }

  private ImmutableSortedSet<Path> getRecursivePaths(ImmutableSortedSet<Path> paths)
      throws IOException {
    ImmutableSortedSet.Builder<Path> allPaths = ImmutableSortedSet.naturalOrder();
    for (Path path : paths) {
      allPaths.add(path);
      projectFilesystem.walkRelativeFileTree(
          path,
          new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                throws IOException {
              allPaths.add(dir);
              return super.preVisitDirectory(dir, attrs);
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              allPaths.add(file);
              return super.visitFile(file, attrs);
            }
          },
          false);
    }
    return allPaths.build();
  }

  @Override
  public ImmutableSortedMap<String, String> getMetadataForArtifact() throws IOException {
    ImmutableSortedMap<String, String> metadata =
        ImmutableSortedMap.copyOf(buildInfoStore.getAllMetadata(buildTarget));

    Preconditions.checkState(
        metadata.containsKey(BuildInfo.MetadataKey.ORIGIN_BUILD_ID),
        "Cache artifact for build target %s is missing metadata %s.",
        buildTarget,
        BuildInfo.MetadataKey.ORIGIN_BUILD_ID);

    return metadata;
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
  public void deleteExistingMetadata() throws IOException {
    buildInfoStore.deleteMetadata(buildTarget);
    projectFilesystem.deleteRecursivelyIfExists(metadataDirectory);
  }

  @Override
  public void calculateOutputSizeAndWriteMetadata(
      FileHashLoader fileHashLoader,
      ImmutableSortedSet<Path> recordedPaths,
      Predicate<Long> shouldWriteOutputHashes)
      throws IOException {
    // Convert all recorded paths to use unix file separators
    String recordedPathsString =
        toJson(
            recordedPaths.stream()
                .map(Object::toString)
                .map(path -> path.replace(File.separator, "/"))
                .collect(ImmutableList.toImmutableList()));
    ImmutableMap.Builder<String, String> artifactMetadataJson = ImmutableMap.builder();
    artifactMetadataJson.put(BuildInfo.MetadataKey.RECORDED_PATHS, recordedPathsString);

    ImmutableSortedSet<Path> outputPaths = getRecursivePaths(recordedPaths);
    long outputSize = getOutputSize(outputPaths);
    artifactMetadataJson.put(BuildInfo.MetadataKey.OUTPUT_SIZE, String.valueOf(outputSize));
    projectFilesystem.writeContentsToPath(
        String.valueOf(outputSize),
        metadataDirectory.resolve(BuildInfo.MetadataKey.ARTIFACT_METADATA));
    if (shouldWriteOutputHashes.apply(outputSize)) {
      // Grab and record the output hashes in the build metadata so that cache hits avoid re-hashing
      // file contents.  Since we use output hashes for input-based rule keys and for detecting
      // non-determinism, we would spend a lot of time re-hashing output paths -- potentially in
      // serialized in a single step. So, do the hashing here to distribute the workload across
      // several threads and cache the results.
      ImmutableSortedMap.Builder<String, String> outputHashes = ImmutableSortedMap.naturalOrder();
      Hasher hasher = Hashing.sha1().newHasher();
      for (Path path : outputPaths) {
        String pathString = path.toString();
        HashCode fileHash = fileHashLoader.get(projectFilesystem, path);
        hasher.putBytes(pathString.getBytes(Charsets.UTF_8));
        hasher.putBytes(fileHash.asBytes());
        outputHashes.put(pathString, fileHash.toString());
      }

      artifactMetadataJson.put(
          BuildInfo.MetadataKey.RECORDED_PATH_HASHES,
          ObjectMappers.WRITER.writeValueAsString(outputHashes.build()));
      artifactMetadataJson.put(BuildInfo.MetadataKey.OUTPUT_HASH, hasher.hash().toString());
    }

    projectFilesystem.writeContentsToPath(
        ObjectMappers.WRITER.writeValueAsString(artifactMetadataJson.build()),
        artifactMetadataFilePath);
  }

  @Override
  public void validateArtifact(Set<Path> extractedFiles) throws IOException {
    // TODO(bertrand): It would be good to validate OUTPUT_HASH and RECORDED_PATH_HASHES, but we
    // don't compute them if the artifact size exceeds the input rule key threshold.
    Optional<String> artifactMetadataFile =
        projectFilesystem.readFileIfItExists(artifactMetadataFilePath);

    Preconditions.checkState(artifactMetadataFile.isPresent());
    ImmutableMap<String, String> artifactMetadata =
        ObjectMappers.readValue(
            artifactMetadataFile.get(), new TypeReference<ImmutableMap<String, String>>() {});
    Preconditions.checkState(artifactMetadata.containsKey(BuildInfo.MetadataKey.RECORDED_PATHS));
    Preconditions.checkState(artifactMetadata.containsKey(BuildInfo.MetadataKey.OUTPUT_SIZE));

    // Check that the output_size of all RECORDED_PATHS matches OUTPUT_SIZE
    long outputSize =
        Long.parseLong(
            Objects.requireNonNull(artifactMetadata.get(BuildInfo.MetadataKey.OUTPUT_SIZE)));
    long realSize = getOutputSize(getPathsForArtifact());
    if (realSize != outputSize) {
      LOG.warn(
          "Target (%s) Artifact output size (%s) doesn't match artifactMetadata OUTPUT_SIZE (%s).",
          buildTarget.getFullyQualifiedName(), realSize, outputSize);
    }
  }

  private long getOutputSize(SortedSet<Path> paths) throws IOException {
    long size = 0;
    for (Path path : paths) {
      if (projectFilesystem.isFile(path)
          && !path.endsWith(BuildInfo.MetadataKey.RECORDED_PATHS)
          && !path.endsWith(BuildInfo.MetadataKey.OUTPUT_SIZE)
          && !path.endsWith(BuildInfo.MetadataKey.OUTPUT_HASH)
          && !path.endsWith(BuildInfo.MetadataKey.RECORDED_PATH_HASHES)
          && !path.endsWith(BuildInfo.MetadataKey.ARTIFACT_METADATA)) {
        size += projectFilesystem.getFileSize(path);
      }
    }
    return size;
  }

  private String toJson(Object value) {
    try {
      return ObjectMappers.WRITER.writeValueAsString(value);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
