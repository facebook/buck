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

package com.facebook.buck.remoteexecution;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.remoteexecution.proto.WorkerRequirements;
import com.facebook.buck.remoteexecution.proto.WorkerRequirements.WorkerPlatformType;
import com.facebook.buck.remoteexecution.proto.WorkerRequirements.WorkerSize;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.MapType;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Provides rule's RE worker requirements based on JSON file */
public final class FileBasedWorkerRequirementsProvider implements WorkerRequirementsProvider {

  public static final WorkerRequirements RETRY_ON_OOM_DEFAULT =
      WorkerRequirements.newBuilder()
          .setWorkerSize(WorkerSize.SMALL)
          .setPlatformType(WorkerPlatformType.LINUX)
          .setShouldTryLargerWorkerOnOom(true)
          .build();

  public static final WorkerRequirements DONT_RETRY_ON_OOM_DEFAULT =
      WorkerRequirements.newBuilder()
          .setWorkerSize(WorkerSize.SMALL)
          .setPlatformType(WorkerPlatformType.LINUX)
          .setShouldTryLargerWorkerOnOom(false)
          .build();

  private static final Logger LOG = Logger.get(FileBasedWorkerRequirementsProvider.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final MapType MAP_TYPE =
      MAPPER.getTypeFactory().constructMapType(Map.class, String.class, Requirements.class);
  private static final JavaType MUTABLE_ACTION_TAGS_TYPE =
      MAPPER.getTypeFactory().constructType(MutableActionTags.class);

  private static final String NO_AUXILIARY_BUILD_TAG = "";

  private final ProjectFilesystem projectFilesystem;
  private final String workerRequirementsFilename;
  private final boolean tryLargerWorkerOnOom;
  private final Cache<Path, Map<ImmutableActionTags, WorkerRequirements>> cache;

  public FileBasedWorkerRequirementsProvider(
      ProjectFilesystem projectFilesystem,
      String workerRequirementsFilename,
      boolean tryLargerWorkerOnOom,
      int cacheSize) {
    this.projectFilesystem = projectFilesystem;
    this.workerRequirementsFilename = workerRequirementsFilename;
    this.tryLargerWorkerOnOom = tryLargerWorkerOnOom;
    cache =
        CacheBuilder.newBuilder()
            .maximumSize(cacheSize)
            .expireAfterAccess(1, TimeUnit.MINUTES)
            .removalListener(
                (RemovalListener<Path, Map<ImmutableActionTags, WorkerRequirements>>)
                    notification ->
                        LOG.debug(
                            "Requirements for file=%s are being evicted, reason=%s",
                            notification.getKey(), notification.getCause()))
            .build();
  }

  private WorkerRequirements resolveDefault() {
    return tryLargerWorkerOnOom ? RETRY_ON_OOM_DEFAULT : DONT_RETRY_ON_OOM_DEFAULT;
  }

  /**
   * Resolve rule's worker requirements.
   *
   * @param target build target
   * @param auxiliaryBuildTag auxiliary tag capturing any custom configurations
   * @return @see com.facebook.buck.remoteexecution.proto.WorkerRequirements based on JSON file,
   *     otherwise @see
   *     com.facebook.buck.remoteexecution.FileBasedWorkerRequirementsProvider.DEFAULT
   */
  @Override
  public WorkerRequirements resolveRequirements(BuildTarget target, String auxiliaryBuildTag) {
    // TODO(nga): must not ignore cell path
    Path filepath =
        projectFilesystem
            .resolve(target.getCellRelativeBasePath().getPath())
            .resolve(workerRequirementsFilename);
    if (!Files.exists(filepath)) {
      return resolveDefault();
    }

    try {
      Map<ImmutableActionTags, WorkerRequirements> requirementsMap =
          cache.get(
              filepath,
              () -> {
                LOG.debug("Parsing a worker requirements file=%s", filepath);
                Map<String, Requirements> rawRequirements =
                    MAPPER.readValue(filepath.toFile(), MAP_TYPE);

                try {
                  Map<ImmutableActionTags, WorkerRequirements> requirements = new HashMap<>();
                  for (Entry<String, Requirements> reqsEntry : rawRequirements.entrySet()) {
                    MutableActionTags mutableTags =
                        MAPPER.readValue(reqsEntry.getKey(), MUTABLE_ACTION_TAGS_TYPE);
                    requirements.put(
                        ImmutableActionTags.of(mutableTags.ruleName, mutableTags.auxiliaryBuildTag),
                        mapRequirements(reqsEntry.getValue()));
                  }
                  return requirements;
                } catch (JsonMappingException | JsonParseException ex) {
                  // TODO(msienkiewicz): Remove the fallback once the old format is unused.
                  LOG.debug(
                      ex.getCause(),
                      "Could not map requirements file to new format. Falling back to old one.");
                  return rawRequirements.entrySet().stream()
                      .collect(
                          Collectors.toMap(
                              reqsEntry ->
                                  ImmutableActionTags.of(
                                      reqsEntry.getKey(), NO_AUXILIARY_BUILD_TAG),
                              reqsEntry -> mapRequirements(reqsEntry.getValue())));
                }
              });

      String ruleName = target.getShortNameAndFlavorPostfix();
      WorkerRequirements requirements =
          requirementsMap.get(ImmutableActionTags.of(ruleName, auxiliaryBuildTag));
      if (requirements == null && !auxiliaryBuildTag.equals(NO_AUXILIARY_BUILD_TAG)) {
        LOG.debug(
            "No requirements found for rule=%s with auxiliary build tag=%s, trying without tag.",
            target.getFullyQualifiedName(), auxiliaryBuildTag);
        requirements =
            requirementsMap.get(ImmutableActionTags.of(ruleName, NO_AUXILIARY_BUILD_TAG));
      }
      if (requirements == null) {
        LOG.debug(
            "No requirements found for rule=%s with auxiliary build tag=%s, using default.",
            target.getFullyQualifiedName(), auxiliaryBuildTag);
        return resolveDefault();
      }
      return requirements;
    } catch (ExecutionException e) {
      LOG.error(e.getCause(), "Unable to parse worker requirements file=%s", filepath.toString());
      return resolveDefault();
    }
  }

  private WorkerRequirements mapRequirements(Requirements reqs) {
    return WorkerRequirements.newBuilder()
        .setWorkerSize(reqs.workerSize)
        .setPlatformType(reqs.platformType)
        // TODO[bskorobogaty]: Should we override this with the value
        // from the file?
        .setShouldTryLargerWorkerOnOom(tryLargerWorkerOnOom)
        .build();
  }

  private static class Requirements {
    public WorkerSize workerSize = WorkerSize.SMALL;

    public WorkerPlatformType platformType = WorkerPlatformType.LINUX;
  }

  /**
   * Represents all tags used to identify an action for the purpose of selecting appropriate Worker
   * Requirements.
   */
  @BuckStyleValue
  interface ActionTags {
    String getRuleName();

    String getAuxiliaryBuildTag();
  }

  private static final class MutableActionTags implements ActionTags {
    public String ruleName = "";
    public String auxiliaryBuildTag = "";

    @Override
    public String getRuleName() {
      return ruleName;
    }

    @Override
    public String getAuxiliaryBuildTag() {
      return auxiliaryBuildTag;
    }
  }
}
