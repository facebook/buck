/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.remoteexecution;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.remoteexecution.proto.WorkerRequirements;
import com.facebook.buck.remoteexecution.proto.WorkerRequirements.WorkerPlatformType;
import com.facebook.buck.remoteexecution.proto.WorkerRequirements.WorkerSize;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.MapType;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Provides rule's RE worker requirements based on JSON file */
public final class WorkerRequirementsProvider {
  public static final WorkerRequirements DEFAULT =
      WorkerRequirements.newBuilder()
          .setWorkerSize(WorkerSize.SMALL)
          .setPlatformType(WorkerPlatformType.LINUX)
          .build();
  private static final Logger LOG = Logger.get(WorkerRequirementsProvider.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final MapType MAP_TYPE =
      MAPPER.getTypeFactory().constructMapType(Map.class, String.class, Requirements.class);

  private final String workerRequirementsFilename;
  private final boolean tryLargerWorkerOnOom;
  private final Cache<Path, Map<String, WorkerRequirements>> cache;

  public WorkerRequirementsProvider(
      String workerRequirementsFilename, boolean tryLargerWorkerOnOom, int cacheSize) {
    this.workerRequirementsFilename = workerRequirementsFilename;
    this.tryLargerWorkerOnOom = tryLargerWorkerOnOom;
    cache =
        CacheBuilder.newBuilder()
            .maximumSize(cacheSize)
            .expireAfterAccess(1, TimeUnit.MINUTES)
            .removalListener(
                (RemovalListener<Path, Map<String, WorkerRequirements>>)
                    notification ->
                        LOG.debug(
                            "Requirements for file=%s are being evicted, reason=%s",
                            notification.getKey(), notification.getCause()))
            .build();
  }

  /**
   * Resolve rule's worker requirements.
   *
   * @param target build rule target
   * @return @see com.facebook.buck.remoteexecution.proto.WorkerRequirements based on JSON file,
   *     otherwise @see com.facebook.buck.remoteexecution.WorkerRequirementsProvider.DEFAULT
   */
  public WorkerRequirements resolveRequirements(BuildTarget target) {
    Path filepath = target.getBasePath().resolve(workerRequirementsFilename);
    if (!Files.exists(filepath)) {
      return DEFAULT;
    }

    try {
      Map<String, WorkerRequirements> requirementsMap =
          cache.get(
              filepath,
              () -> {
                LOG.debug("Parsing a worker requirements file=%s", filepath);

                Map<String, Requirements> requirements =
                    MAPPER.readValue(filepath.toFile(), MAP_TYPE);
                return requirements.entrySet().stream()
                    .collect(
                        Collectors.toMap(
                            Entry::getKey,
                            e ->
                                WorkerRequirements.newBuilder()
                                    .setWorkerSize(e.getValue().workerSize)
                                    .setPlatformType(e.getValue().platformType)
                                    // TODO[bskorobogaty]: Should we override this with the value
                                    // from the file?
                                    .setShouldTryLargerWorkerOnOom(tryLargerWorkerOnOom)
                                    .build()));
              });
      return Optional.ofNullable(requirementsMap.get(target.getShortName()))
          .orElseGet(
              () -> {
                LOG.debug(
                    "No requirements found for rule=%s, using default",
                    target.getFullyQualifiedName());
                return DEFAULT;
              });
    } catch (ExecutionException e) {
      LOG.error(e.getCause(), "Unable to parse worker requirements file=%s", filepath.toString());
      return DEFAULT;
    }
  }

  private static class Requirements {
    @SuppressWarnings("unused")
    public WorkerSize workerSize = WorkerSize.SMALL;

    @SuppressWarnings("unused")
    public WorkerPlatformType platformType = WorkerPlatformType.LINUX;
  }
}
