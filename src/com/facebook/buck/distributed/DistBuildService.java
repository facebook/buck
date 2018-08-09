/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.distributed;

import static com.facebook.buck.distributed.ClientStatsTracker.DistBuildClientStat.SET_BUCK_VERSION;
import static com.facebook.buck.distributed.ClientStatsTracker.DistBuildClientStat.UPLOAD_BUCK_DOT_FILES;
import static com.facebook.buck.distributed.ClientStatsTracker.DistBuildClientStat.UPLOAD_MISSING_FILES;
import static com.facebook.buck.distributed.ClientStatsTracker.DistBuildClientStat.UPLOAD_TARGET_GRAPH;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.distributed.thrift.AppendBuildSlaveEventsRequest;
import com.facebook.buck.distributed.thrift.BuckVersion;
import com.facebook.buck.distributed.thrift.BuildJob;
import com.facebook.buck.distributed.thrift.BuildJobState;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashEntry;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashes;
import com.facebook.buck.distributed.thrift.BuildMode;
import com.facebook.buck.distributed.thrift.BuildSlaveEvent;
import com.facebook.buck.distributed.thrift.BuildSlaveEventType;
import com.facebook.buck.distributed.thrift.BuildSlaveEventsQuery;
import com.facebook.buck.distributed.thrift.BuildSlaveEventsRange;
import com.facebook.buck.distributed.thrift.BuildSlaveFinishedStats;
import com.facebook.buck.distributed.thrift.BuildSlaveRunId;
import com.facebook.buck.distributed.thrift.BuildSlaveStatus;
import com.facebook.buck.distributed.thrift.BuildStatus;
import com.facebook.buck.distributed.thrift.BuildStatusRequest;
import com.facebook.buck.distributed.thrift.CASContainsRequest;
import com.facebook.buck.distributed.thrift.CreateBuildRequest;
import com.facebook.buck.distributed.thrift.CreateBuildResponse;
import com.facebook.buck.distributed.thrift.EnqueueMinionsRequest;
import com.facebook.buck.distributed.thrift.FetchBuildGraphRequest;
import com.facebook.buck.distributed.thrift.FetchBuildSlaveFinishedStatsRequest;
import com.facebook.buck.distributed.thrift.FetchBuildSlaveStatusRequest;
import com.facebook.buck.distributed.thrift.FetchRuleKeyLogsRequest;
import com.facebook.buck.distributed.thrift.FetchSourceFilesRequest;
import com.facebook.buck.distributed.thrift.FetchSourceFilesResponse;
import com.facebook.buck.distributed.thrift.FileInfo;
import com.facebook.buck.distributed.thrift.FrontendRequest;
import com.facebook.buck.distributed.thrift.FrontendRequestType;
import com.facebook.buck.distributed.thrift.FrontendResponse;
import com.facebook.buck.distributed.thrift.LogLineBatchRequest;
import com.facebook.buck.distributed.thrift.MinionRequirements;
import com.facebook.buck.distributed.thrift.MinionType;
import com.facebook.buck.distributed.thrift.MultiGetBuildSlaveEventsRequest;
import com.facebook.buck.distributed.thrift.MultiGetBuildSlaveLogDirRequest;
import com.facebook.buck.distributed.thrift.MultiGetBuildSlaveLogDirResponse;
import com.facebook.buck.distributed.thrift.MultiGetBuildSlaveRealTimeLogsRequest;
import com.facebook.buck.distributed.thrift.MultiGetBuildSlaveRealTimeLogsResponse;
import com.facebook.buck.distributed.thrift.PathInfo;
import com.facebook.buck.distributed.thrift.ReportCoordinatorAliveRequest;
import com.facebook.buck.distributed.thrift.RuleKeyLogEntry;
import com.facebook.buck.distributed.thrift.SequencedBuildSlaveEvent;
import com.facebook.buck.distributed.thrift.SetBuckDotFilePathsRequest;
import com.facebook.buck.distributed.thrift.SetBuckVersionRequest;
import com.facebook.buck.distributed.thrift.SetCoordinatorRequest;
import com.facebook.buck.distributed.thrift.SetFinalBuildStatusRequest;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.distributed.thrift.StartBuildRequest;
import com.facebook.buck.distributed.thrift.StoreBuildGraphRequest;
import com.facebook.buck.distributed.thrift.StoreBuildSlaveFinishedStatsRequest;
import com.facebook.buck.distributed.thrift.StoreLocalChangesRequest;
import com.facebook.buck.distributed.thrift.UpdateBuildSlaveBuildStatusRequest;
import com.facebook.buck.distributed.thrift.UpdateBuildSlaveStatusRequest;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.slb.ThriftProtocol;
import com.facebook.buck.slb.ThriftUtil;
import com.facebook.buck.util.cache.FileHashCache;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class DistBuildService implements Closeable {
  private static final Logger LOG = Logger.get(DistBuildService.class);
  private static final ThriftProtocol PROTOCOL_FOR_CLIENT_ONLY_STRUCTS = ThriftProtocol.COMPACT;

  private final FrontendService service;
  private final String username;

  /** Exception thrown when CreateBuildRequest is rejected (with a rejection message). */
  public static class DistBuildRejectedException extends Exception {

    private static final String MESSAGE_PREFIX = "Distributed build will not run: ";

    public DistBuildRejectedException(String message) {
      super(MESSAGE_PREFIX + message);
    }
  }

  public DistBuildService(FrontendService service, String username) {
    Preconditions.checkNotNull(username, "Username needs to be set for distributed build.");
    this.service = service;
    this.username = username;
  }

  public MultiGetBuildSlaveRealTimeLogsResponse fetchSlaveLogLines(
      StampedeId stampedeId, List<LogLineBatchRequest> logLineRequests) throws IOException {

    MultiGetBuildSlaveRealTimeLogsRequest getLogLinesRequest =
        new MultiGetBuildSlaveRealTimeLogsRequest();
    getLogLinesRequest.setStampedeId(stampedeId);
    getLogLinesRequest.setBatches(logLineRequests);

    FrontendRequest request = new FrontendRequest();
    request.setType(FrontendRequestType.GET_BUILD_SLAVE_REAL_TIME_LOGS);
    request.setMultiGetBuildSlaveRealTimeLogsRequest(getLogLinesRequest);
    FrontendResponse response = makeRequestChecked(request);
    return response.getMultiGetBuildSlaveRealTimeLogsResponse();
  }

  public MultiGetBuildSlaveLogDirResponse fetchBuildSlaveLogDir(
      StampedeId stampedeId, List<BuildSlaveRunId> runIds) throws IOException {

    MultiGetBuildSlaveLogDirRequest getBuildSlaveLogDirRequest =
        new MultiGetBuildSlaveLogDirRequest();
    getBuildSlaveLogDirRequest.setStampedeId(stampedeId);
    getBuildSlaveLogDirRequest.setBuildSlaveRunIds(runIds);

    FrontendRequest request = new FrontendRequest();
    request.setType(FrontendRequestType.GET_BUILD_SLAVE_LOG_DIR);
    request.setMultiGetBuildSlaveLogDirRequest(getBuildSlaveLogDirRequest);
    FrontendResponse response = makeRequestChecked(request);
    Preconditions.checkState(response.isSetMultiGetBuildSlaveLogDirResponse());
    return response.getMultiGetBuildSlaveLogDirResponse();
  }

  public void uploadTargetGraph(
      BuildJobState buildJobState, StampedeId stampedeId, ClientStatsTracker distBuildClientStats)
      throws IOException {
    distBuildClientStats.startTimer(UPLOAD_TARGET_GRAPH);

    // Serialize and send the whole buildJobState
    StoreBuildGraphRequest storeBuildGraphRequest = new StoreBuildGraphRequest();
    storeBuildGraphRequest.setStampedeId(stampedeId);
    storeBuildGraphRequest.setBuildGraph(BuildJobStateSerializer.serialize(buildJobState));

    FrontendRequest request = new FrontendRequest();
    request.setType(FrontendRequestType.STORE_BUILD_GRAPH);
    request.setStoreBuildGraphRequest(storeBuildGraphRequest);
    makeRequestChecked(request);
    distBuildClientStats.stopTimer(UPLOAD_TARGET_GRAPH);
    // No response expected.
  }

  public ListenableFuture<Void> uploadMissingFilesAsync(
      Map<Integer, ProjectFilesystem> localFilesystemsByCell,
      List<BuildJobStateFileHashes> fileHashes,
      ClientStatsTracker distBuildClientStats,
      ListeningExecutorService executorService) {
    distBuildClientStats.startTimer(UPLOAD_MISSING_FILES);
    List<PathInfo> requiredFiles = new ArrayList<>();
    for (BuildJobStateFileHashes filesystem : fileHashes) {
      if (!filesystem.isSetEntries()) {
        continue;
      }
      ProjectFilesystem cellFilesystem =
          Preconditions.checkNotNull(localFilesystemsByCell.get(filesystem.getCellIndex()));
      for (BuildJobStateFileHashEntry file : filesystem.entries) {
        if (file.isSetRootSymLink()) {
          LOG.info("File with path [%s] is a symlink. Skipping upload..", file.path.getPath());
          continue;
        } else if (file.isIsDirectory()) {
          LOG.info("Path [%s] is a directory. Skipping upload..", file.path.getPath());
          continue;
        } else if (file.isPathIsAbsolute()) {
          LOG.info("Path [%s] is absolute. Skipping upload..", file.path.getPath());
          continue;
        }

        if (!file.isSetSha1()) {
          throw new RuntimeException(
              String.format("Missing content hash for path [%s].", file.path.getPath()));
        }

        PathInfo pathInfo = new PathInfo();
        pathInfo.setPath(cellFilesystem.resolve(file.getPath().getPath()).toString());
        pathInfo.setContentHash(file.getSha1());
        requiredFiles.add(pathInfo);
      }
    }

    LOG.info(
        "%d files are required to be uploaded. Now checking which ones are already present...",
        requiredFiles.size());

    return Futures.transform(
        uploadMissingFilesAsync(requiredFiles, executorService),
        uploadCount -> {
          distBuildClientStats.setMissingFilesUploadedCount(uploadCount);
          distBuildClientStats.stopTimer(UPLOAD_MISSING_FILES);
          return null;
        },
        executorService);
  }

  /**
   * This function takes a list of files which we need to be present in the CAS, and uploads only
   * the missing files. So it makes 2 requests to the server: {@link CASContainsRequest} and {@link
   * StoreLocalChangesRequest}.
   *
   * @param absPathsAndHashes List of {@link PathInfo} objects with absolute paths and content SHA1
   *     of the files which need to be uploaded.
   * @param executorService Executor to enable concurrent file reads and upload request.
   * @return A Future containing the number of missing files which were uploaded to the CAS. This
   *     future completes when the upload finishes.
   */
  private ListenableFuture<Integer> uploadMissingFilesAsync(
      List<PathInfo> absPathsAndHashes, ListeningExecutorService executorService) {

    Map<String, PathInfo> sha1ToPathInfo = new HashMap<>();
    for (PathInfo file : absPathsAndHashes) {
      sha1ToPathInfo.put(file.getContentHash(), file);
    }

    List<String> contentHashes = ImmutableList.copyOf(sha1ToPathInfo.keySet());
    CASContainsRequest containsReq = new CASContainsRequest();
    containsReq.setContentSha1s(contentHashes);
    ListenableFuture<FrontendResponse> responseFuture =
        executorService.submit(
            () ->
                makeRequestChecked(
                    new FrontendRequest()
                        .setType(FrontendRequestType.CAS_CONTAINS)
                        .setCasContainsRequest(containsReq)));

    ListenableFuture<List<FileInfo>> filesToBeUploaded =
        Futures.transformAsync(
            responseFuture,
            response -> {
              Preconditions.checkState(
                  response.getCasContainsResponse().exists.size() == contentHashes.size());
              List<Boolean> isPresent = response.getCasContainsResponse().exists;
              List<ListenableFuture<FileInfo>> missingFilesFutureList = new LinkedList<>();
              for (int i = 0; i < isPresent.size(); ++i) {
                if (isPresent.get(i)) {
                  continue;
                }

                String contentHash = contentHashes.get(i);

                // TODO(shivanker): We should upload the missing files in batches, or it might OOM.
                missingFilesFutureList.add(
                    executorService.submit(
                        () -> {
                          FileInfo file = new FileInfo();
                          file.setContentHash(contentHash);
                          try {
                            file.setContent(
                                Files.readAllBytes(
                                    Paths.get(
                                        Preconditions.checkNotNull(sha1ToPathInfo.get(contentHash))
                                            .getPath())));
                          } catch (IOException e) {
                            throw new IOException(
                                String.format(
                                    "Failed to read file for uploading to server: [%s]",
                                    sha1ToPathInfo.get(contentHash).getPath()),
                                e);
                          }
                          return file;
                        }));
              }

              LOG.info(
                  "%d out of %d files already exist in the CAS. Uploading %d files..",
                  sha1ToPathInfo.size() - missingFilesFutureList.size(),
                  sha1ToPathInfo.size(),
                  missingFilesFutureList.size());

              return Futures.allAsList(missingFilesFutureList);
            },
            executorService);

    return Futures.transform(
        filesToBeUploaded,
        fileList -> {
          StoreLocalChangesRequest storeReq = new StoreLocalChangesRequest();
          storeReq.setFiles(fileList);
          try {
            makeRequestChecked(
                new FrontendRequest()
                    .setType(FrontendRequestType.STORE_LOCAL_CHANGES)
                    .setStoreLocalChangesRequest(storeReq));
            // No response expected.
          } catch (IOException e) {
            throw new HumanReadableException(
                e, "Failed to upload [%d] missing files.", fileList.size());
          }
          return fileList.size();
        },
        executorService);
  }

  public BuildJob createBuild(
      BuildId buildId,
      BuildMode buildMode,
      MinionRequirements minionRequirements,
      String repository,
      String tenantId,
      List<String> buildTargets,
      String buildLabel)
      throws IOException, DistBuildRejectedException {
    Preconditions.checkArgument(
        buildMode == BuildMode.REMOTE_BUILD
            || buildMode == BuildMode.DISTRIBUTED_BUILD_WITH_REMOTE_COORDINATOR
            || buildMode == BuildMode.DISTRIBUTED_BUILD_WITH_LOCAL_COORDINATOR,
        "BuckBuildType [%s=%d] is currently not supported.",
        buildMode.toString(),
        buildMode.ordinal());

    // Tell server to create the build and get the build id.
    CreateBuildRequest createBuildRequest = new CreateBuildRequest();
    createBuildRequest
        .setCreateTimestampMillis(System.currentTimeMillis())
        .setBuckBuildUuid(buildId.toString())
        .setBuildMode(buildMode)
        .setUsername(username)
        .setBuildTargets(buildTargets)
        .setMinionRequirements(minionRequirements)
        // TODO(alisdair): remove in future once minion requirements fully supported.
        .setTotalNumberOfMinions(DistBuildUtil.countMinions(minionRequirements))
        .setBuildLabel(buildLabel);

    if (repository != null && repository.length() > 0) {
      createBuildRequest.setRepository(repository);
    }

    if (tenantId != null && tenantId.length() > 0) {
      createBuildRequest.setTenantId(tenantId);
    }

    FrontendRequest request = new FrontendRequest();
    request.setType(FrontendRequestType.CREATE_BUILD);
    request.setCreateBuildRequest(createBuildRequest);
    FrontendResponse response = makeRequestChecked(request);

    CreateBuildResponse createBuildResponse = response.getCreateBuildResponse();
    if (createBuildResponse.isSetWasAccepted() && !createBuildResponse.wasAccepted) {
      throw new DistBuildRejectedException(createBuildResponse.getRejectionMessage());
    }

    return createBuildResponse.getBuildJob();
  }

  public BuildJob startBuild(StampedeId id) throws IOException {
    return startBuild(id, true);
  }

  /**
   * Make a start-build request with custom value for {@param enqueueJob}.
   *
   * @param id - Stampede id for the build you want to start.
   * @param enqueueJob - Whether or not this job should be enqueued on the coordinator queue.start
   * @return - Latest BuildJob spec.
   */
  public BuildJob startBuild(StampedeId id, boolean enqueueJob) throws IOException {
    // Start the build
    StartBuildRequest startRequest = new StartBuildRequest();
    startRequest.setStampedeId(id);
    startRequest.setEnqueueJob(enqueueJob);
    FrontendRequest request = new FrontendRequest();
    request.setType(FrontendRequestType.START_BUILD);
    request.setStartBuildRequest(startRequest);
    FrontendResponse response = makeRequestChecked(request);

    BuildJob job = response.getStartBuildResponse().getBuildJob();
    Preconditions.checkState(job.getStampedeId().equals(id));
    return job;
  }

  public BuildJob getCurrentBuildJobState(StampedeId id) throws IOException {
    BuildStatusRequest statusRequest = new BuildStatusRequest();
    statusRequest.setStampedeId(id);
    FrontendRequest request = new FrontendRequest();
    request.setType(FrontendRequestType.BUILD_STATUS);
    request.setBuildStatusRequest(statusRequest);
    FrontendResponse response = makeRequestChecked(request);

    BuildJob job = response.getBuildStatusResponse().getBuildJob();
    Preconditions.checkState(job.getStampedeId().equals(id));
    return job;
  }

  public BuildJobState fetchBuildJobState(StampedeId stampedeId) throws IOException {
    FrontendRequest request = createFetchBuildGraphRequest(stampedeId);
    FrontendResponse response = makeRequestChecked(request);

    Preconditions.checkState(response.isSetFetchBuildGraphResponse());
    Preconditions.checkState(response.getFetchBuildGraphResponse().isSetBuildGraph());
    Preconditions.checkState(response.getFetchBuildGraphResponse().getBuildGraph().length > 0);

    return BuildJobStateSerializer.deserialize(
        response.getFetchBuildGraphResponse().getBuildGraph());
  }

  public static FrontendRequest createFetchBuildGraphRequest(StampedeId stampedeId) {
    FetchBuildGraphRequest fetchBuildGraphRequest = new FetchBuildGraphRequest();
    fetchBuildGraphRequest.setStampedeId(stampedeId);
    FrontendRequest frontendRequest = new FrontendRequest();
    frontendRequest.setType(FrontendRequestType.FETCH_BUILD_GRAPH);
    frontendRequest.setFetchBuildGraphRequest(fetchBuildGraphRequest);
    return frontendRequest;
  }

  public ImmutableMap<String, byte[]> multiFetchSourceFiles(Set<String> hashCodes)
      throws IOException {
    FrontendRequest request = createFetchSourceFilesRequest(hashCodes);
    FrontendResponse response = makeRequestChecked(request);

    Preconditions.checkState(response.isSetFetchSourceFilesResponse());
    Preconditions.checkState(response.getFetchSourceFilesResponse().isSetFiles());
    FetchSourceFilesResponse fetchSourceFilesResponse = response.getFetchSourceFilesResponse();

    Preconditions.checkState(hashCodes.size() == fetchSourceFilesResponse.getFilesSize());
    ImmutableMap.Builder<String, byte[]> result = new ImmutableMap.Builder<>();
    for (FileInfo fileInfo : fetchSourceFilesResponse.getFiles()) {
      Preconditions.checkNotNull(fileInfo);
      Preconditions.checkNotNull(fileInfo.getContentHash());
      Preconditions.checkNotNull(fileInfo.getContent());
      result.put(fileInfo.getContentHash(), fileInfo.getContent());
    }

    return result.build();
  }

  public byte[] fetchSourceFile(String hashCode) throws IOException {
    ImmutableMap<String, byte[]> result = multiFetchSourceFiles(ImmutableSet.of(hashCode));
    return Preconditions.checkNotNull(result.get(hashCode));
  }

  public static FrontendRequest createFetchSourceFilesRequest(Set<String> fileHashes) {
    FetchSourceFilesRequest fetchSourceFileRequest = new FetchSourceFilesRequest();
    fetchSourceFileRequest.setContentHashes(ImmutableList.copyOf(fileHashes));
    FrontendRequest frontendRequest = new FrontendRequest();
    frontendRequest.setType(FrontendRequestType.FETCH_SRC_FILES);
    frontendRequest.setFetchSourceFilesRequest(fetchSourceFileRequest);
    return frontendRequest;
  }

  public static FrontendRequest createFrontendBuildStatusRequest(StampedeId stampedeId) {
    BuildStatusRequest buildStatusRequest = new BuildStatusRequest();
    buildStatusRequest.setStampedeId(stampedeId);
    FrontendRequest frontendRequest = new FrontendRequest();
    frontendRequest.setType(FrontendRequestType.BUILD_STATUS);
    frontendRequest.setBuildStatusRequest(buildStatusRequest);
    return frontendRequest;
  }

  public void setBuckVersion(
      StampedeId id, BuckVersion buckVersion, ClientStatsTracker distBuildClientStats)
      throws IOException {
    distBuildClientStats.startTimer(SET_BUCK_VERSION);
    SetBuckVersionRequest setBuckVersionRequest = new SetBuckVersionRequest();
    setBuckVersionRequest.setStampedeId(id);
    setBuckVersionRequest.setBuckVersion(buckVersion);
    FrontendRequest request = new FrontendRequest();
    request.setType(FrontendRequestType.SET_BUCK_VERSION);
    request.setSetBuckVersionRequest(setBuckVersionRequest);
    makeRequestChecked(request);
    distBuildClientStats.stopTimer(SET_BUCK_VERSION);
  }

  public void setBuckDotFiles(StampedeId id, List<PathInfo> dotFilesRelativePaths)
      throws IOException {
    SetBuckDotFilePathsRequest storeBuckDotFilesRequest = new SetBuckDotFilePathsRequest();
    storeBuckDotFilesRequest.setStampedeId(id);
    storeBuckDotFilesRequest.setDotFiles(dotFilesRelativePaths);
    FrontendRequest request = new FrontendRequest();
    request.setType(FrontendRequestType.SET_DOTFILE_PATHS);
    request.setSetBuckDotFilePathsRequest(storeBuckDotFilesRequest);
    makeRequestChecked(request);
  }

  public ListenableFuture<Void> uploadBuckDotFilesAsync(
      StampedeId id,
      ProjectFilesystem filesystem,
      FileHashCache fileHashCache,
      ClientStatsTracker distBuildClientStats,
      ListeningExecutorService executorService) {
    distBuildClientStats.startTimer(UPLOAD_BUCK_DOT_FILES);
    ListenableFuture<List<Path>> pathsFuture =
        executorService.submit(
            () -> {
              List<Path> buckDotFilesExceptConfig = new ArrayList<>();
              for (Path path : filesystem.getDirectoryContents(filesystem.getRootPath())) {
                String fileName = path.getFileName().toString();
                if (!filesystem.isDirectory(path)
                    && !filesystem.isSymLink(path)
                    && fileName.startsWith(".")
                    && fileName.contains("buck")
                    && !fileName.startsWith(".buckconfig")) {
                  buckDotFilesExceptConfig.add(path);
                }
              }

              return buckDotFilesExceptConfig;
            });

    ListenableFuture<Void> setFilesFuture =
        Futures.transformAsync(
            pathsFuture,
            paths -> {
              List<PathInfo> relativePathEntries = new LinkedList<>();
              for (Path path : paths) {
                PathInfo pathInfoObject = new PathInfo();
                pathInfoObject.setPath(path.toString());
                pathInfoObject.setContentHash(fileHashCache.get(path.toAbsolutePath()).toString());
                relativePathEntries.add(pathInfoObject);
              }

              setBuckDotFiles(id, relativePathEntries);
              return Futures.immediateFuture(null);
            },
            executorService);

    ListenableFuture<?> uploadFilesFuture =
        Futures.transformAsync(
            pathsFuture,
            paths -> {
              List<PathInfo> absolutePathEntries = new LinkedList<>();
              for (Path path : paths) {
                PathInfo pathInfoObject = new PathInfo();
                pathInfoObject.setPath(path.toAbsolutePath().toString());
                pathInfoObject.setContentHash(fileHashCache.get(path.toAbsolutePath()).toString());
                absolutePathEntries.add(pathInfoObject);
              }

              return uploadMissingFilesAsync(absolutePathEntries, executorService);
            },
            executorService);

    ListenableFuture<Void> resultFuture =
        Futures.transform(
            Futures.allAsList(ImmutableList.of(setFilesFuture, uploadFilesFuture)),
            input -> null,
            MoreExecutors.directExecutor());

    resultFuture.addListener(
        () -> distBuildClientStats.stopTimer(UPLOAD_BUCK_DOT_FILES), executorService);

    return resultFuture;
  }

  /**
   * Publishes generic BuildSlaveEvents, so that they can be downloaded by distributed build client.
   */
  public void uploadBuildSlaveEvents(
      StampedeId stampedeId, BuildSlaveRunId runId, List<BuildSlaveEvent> events)
      throws IOException {
    Stopwatch watch = Stopwatch.createStarted();
    LOG.debug(String.format("Uploading [%d] BuildSlaveEvents.", events.size()));
    AppendBuildSlaveEventsRequest request = new AppendBuildSlaveEventsRequest();
    request.setStampedeId(stampedeId);
    request.setBuildSlaveRunId(runId);
    for (BuildSlaveEvent slaveEvent : events) {
      request.addToEvents(
          ThriftUtil.serializeToByteBuffer(PROTOCOL_FOR_CLIENT_ONLY_STRUCTS, slaveEvent));
    }

    FrontendRequest frontendRequest = new FrontendRequest();
    frontendRequest.setType(FrontendRequestType.APPEND_BUILD_SLAVE_EVENTS);
    frontendRequest.setAppendBuildSlaveEventsRequest(request);
    makeRequestChecked(frontendRequest);
    LOG.debug(
        "Uploaded [%d] BuildSlaveEvents in [%dms].",
        events.size(), watch.elapsed(TimeUnit.MILLISECONDS));
  }

  /**
   * Let the client no that there are no more build rule finished events on the way.
   *
   * @param stampedeId
   * @param runId
   * @throws IOException
   */
  public void sendAllBuildRulesPublishedEvent(
      StampedeId stampedeId, BuildSlaveRunId runId, long timeMillis) throws IOException {
    LOG.info("Sending all build rules finished event");
    AppendBuildSlaveEventsRequest request = new AppendBuildSlaveEventsRequest();
    request.setStampedeId(stampedeId);
    request.setBuildSlaveRunId(runId);

    BuildSlaveEvent buildSlaveEvent =
        DistBuildUtil.createBuildSlaveEvent(
            BuildSlaveEventType.ALL_BUILD_RULES_FINISHED_EVENT, timeMillis);
    request.addToEvents(
        ThriftUtil.serializeToByteBuffer(PROTOCOL_FOR_CLIENT_ONLY_STRUCTS, buildSlaveEvent));

    FrontendRequest frontendRequest = new FrontendRequest();
    frontendRequest.setType(FrontendRequestType.APPEND_BUILD_SLAVE_EVENTS);
    frontendRequest.setAppendBuildSlaveEventsRequest(request);
    makeRequestChecked(frontendRequest);
  }

  /**
   * Sets the build status for minion with given run ID.
   *
   * @throws IOException
   */
  public void updateBuildSlaveBuildStatus(
      StampedeId stampedeId, String runIdStr, BuildStatus status) throws IOException {
    UpdateBuildSlaveBuildStatusRequest request = new UpdateBuildSlaveBuildStatusRequest();
    request.setStampedeId(stampedeId);
    BuildSlaveRunId buildSlaveRunId = new BuildSlaveRunId();
    buildSlaveRunId.setId(runIdStr);
    request.setRunId(buildSlaveRunId);
    request.setBuildStatus(status);

    FrontendRequest frontendRequest = new FrontendRequest();
    frontendRequest.setType(FrontendRequestType.UPDATE_BUILD_SLAVE_BUILD_STATUS);
    frontendRequest.setUpdateBuildSlaveBuildStatusRequest(request);
    makeRequestChecked(frontendRequest);
  }

  public void updateBuildSlaveStatus(
      StampedeId stampedeId, BuildSlaveRunId runId, BuildSlaveStatus status) throws IOException {
    UpdateBuildSlaveStatusRequest request = new UpdateBuildSlaveStatusRequest();
    request.setStampedeId(stampedeId);
    request.setBuildSlaveRunId(runId);
    request.setBuildSlaveStatus(ThriftUtil.serialize(PROTOCOL_FOR_CLIENT_ONLY_STRUCTS, status));

    FrontendRequest frontendRequest = new FrontendRequest();
    frontendRequest.setType(FrontendRequestType.UPDATE_BUILD_SLAVE_STATUS);
    frontendRequest.setUpdateBuildSlaveStatusRequest(request);
    makeRequestChecked(frontendRequest);
  }

  public BuildSlaveEventsQuery createBuildSlaveEventsQuery(
      StampedeId stampedeId, BuildSlaveRunId runId, int firstEventToBeFetched) {
    BuildSlaveEventsQuery query = new BuildSlaveEventsQuery();
    query.setStampedeId(stampedeId);
    query.setBuildSlaveRunId(runId);
    query.setFirstEventNumber(firstEventToBeFetched);
    return query;
  }

  /** Fetch BuildSlaveEvents based on a list of queries. */
  public List<BuildSlaveEventWrapper> multiGetBuildSlaveEvents(
      List<BuildSlaveEventsQuery> eventsQueries) throws IOException {
    LOG.info("Fetching events from Frontend");
    MultiGetBuildSlaveEventsRequest request = new MultiGetBuildSlaveEventsRequest();
    request.setRequests(eventsQueries);
    FrontendRequest frontendRequest = new FrontendRequest();
    frontendRequest.setType(FrontendRequestType.MULTI_GET_BUILD_SLAVE_EVENTS);
    frontendRequest.setMultiGetBuildSlaveEventsRequest(request);
    FrontendResponse response = makeRequestChecked(frontendRequest);

    Preconditions.checkState(response.isSetMultiGetBuildSlaveEventsResponse());
    Preconditions.checkState(response.getMultiGetBuildSlaveEventsResponse().isSetResponses());

    List<BuildSlaveEventWrapper> result = Lists.newArrayList();
    for (BuildSlaveEventsRange eventsRange :
        response.getMultiGetBuildSlaveEventsResponse().getResponses()) {
      Preconditions.checkState(eventsRange.isSetSuccess());
      if (!eventsRange.isSuccess()) {
        LOG.error(
            String.format(
                "Error in BuildSlaveEventsRange received from MultiGetBuildSlaveEvents: [%s]",
                eventsRange.getErrorMessage()));
        continue;
      }

      Preconditions.checkState(eventsRange.isSetEvents());
      for (SequencedBuildSlaveEvent slaveEventWithSeqId : eventsRange.getEvents()) {
        BuildSlaveEvent event = new BuildSlaveEvent();
        ThriftUtil.deserialize(
            PROTOCOL_FOR_CLIENT_ONLY_STRUCTS, slaveEventWithSeqId.getEvent(), event);
        result.add(
            new BuildSlaveEventWrapper(
                slaveEventWithSeqId.getEventNumber(),
                eventsRange.getQuery().getBuildSlaveRunId(),
                event));
      }
    }

    LOG.info(String.format("Fetched events from Frontend. Got [%d] events.", result.size()));
    return result;
  }

  public Optional<BuildSlaveStatus> fetchBuildSlaveStatus(
      StampedeId stampedeId, BuildSlaveRunId runId) throws IOException {
    FetchBuildSlaveStatusRequest request = new FetchBuildSlaveStatusRequest();
    request.setStampedeId(stampedeId);
    request.setBuildSlaveRunId(runId);
    FrontendRequest frontendRequest = new FrontendRequest();
    frontendRequest.setType(FrontendRequestType.FETCH_BUILD_SLAVE_STATUS);
    frontendRequest.setFetchBuildSlaveStatusRequest(request);
    FrontendResponse response = makeRequestChecked(frontendRequest);

    Preconditions.checkState(response.isSetFetchBuildSlaveStatusResponse());
    if (!response.getFetchBuildSlaveStatusResponse().isSetBuildSlaveStatus()) {
      return Optional.empty();
    }

    BuildSlaveStatus status = new BuildSlaveStatus();
    ThriftUtil.deserialize(
        PROTOCOL_FOR_CLIENT_ONLY_STRUCTS,
        response.getFetchBuildSlaveStatusResponse().getBuildSlaveStatus(),
        status);
    return Optional.of(status);
  }

  public void storeBuildSlaveFinishedStats(
      StampedeId stampedeId, BuildSlaveRunId runId, BuildSlaveFinishedStats status)
      throws IOException {
    StoreBuildSlaveFinishedStatsRequest request = new StoreBuildSlaveFinishedStatsRequest();
    request.setStampedeId(stampedeId);
    request.setBuildSlaveRunId(runId);
    request.setBuildSlaveFinishedStats(
        ThriftUtil.serialize(PROTOCOL_FOR_CLIENT_ONLY_STRUCTS, status));

    FrontendRequest frontendRequest = new FrontendRequest();
    frontendRequest.setType(FrontendRequestType.STORE_BUILD_SLAVE_FINISHED_STATS);
    frontendRequest.setStoreBuildSlaveFinishedStatsRequest(request);
    makeRequestChecked(frontendRequest);
  }

  public Optional<BuildSlaveFinishedStats> fetchBuildSlaveFinishedStats(
      StampedeId stampedeId, BuildSlaveRunId runId) throws IOException {
    FetchBuildSlaveFinishedStatsRequest request = new FetchBuildSlaveFinishedStatsRequest();
    request.setStampedeId(stampedeId);
    request.setBuildSlaveRunId(runId);
    FrontendRequest frontendRequest = new FrontendRequest();
    frontendRequest.setType(FrontendRequestType.FETCH_BUILD_SLAVE_FINISHED_STATS);
    frontendRequest.setFetchBuildSlaveFinishedStatsRequest(request);
    FrontendResponse response = makeRequestChecked(frontendRequest);

    Preconditions.checkState(response.isSetFetchBuildSlaveFinishedStatsResponse());
    if (!response.getFetchBuildSlaveFinishedStatsResponse().isSetBuildSlaveFinishedStats()) {
      return Optional.empty();
    }

    BuildSlaveFinishedStats status = new BuildSlaveFinishedStats();
    ThriftUtil.deserialize(
        PROTOCOL_FOR_CLIENT_ONLY_STRUCTS,
        response.getFetchBuildSlaveFinishedStatsResponse().getBuildSlaveFinishedStats(),
        status);
    return Optional.of(status);
  }

  /**
   * Fetch rule key logs as name says. RKL are filtered by repository, schedule type and distributed
   * flag.
   */
  public List<RuleKeyLogEntry> fetchRuleKeyLogs(
      Collection<String> ruleKeys,
      String repository,
      String scheduleType,
      boolean distributedBuildModeEnabled)
      throws IOException {
    FetchRuleKeyLogsRequest request = new FetchRuleKeyLogsRequest();
    request.setRuleKeys(Lists.newArrayList(ruleKeys));
    request.setRepository(repository);
    request.setScheduleType(scheduleType);
    request.setDistributedBuildModeEnabled(distributedBuildModeEnabled);

    FrontendRequest frontendRequest = new FrontendRequest();
    frontendRequest.setType(FrontendRequestType.FETCH_RULE_KEY_LOGS);
    frontendRequest.setFetchRuleKeyLogsRequest(request);

    FrontendResponse response = makeRequestChecked(frontendRequest);

    Preconditions.checkState(response.isSetFetchRuleKeyLogsResponse());
    Preconditions.checkState(response.getFetchRuleKeyLogsResponse().isSetRuleKeyLogs());

    return response.getFetchRuleKeyLogsResponse().getRuleKeyLogs();
  }

  /** Sets the final BuildStatus of the BuildJob. */
  public void setFinalBuildStatus(StampedeId stampedeId, BuildStatus status, String statusMessage)
      throws IOException {
    LOG.info("Setting final build status [%s] with message: %s", status.toString(), statusMessage);
    Preconditions.checkArgument(BuildStatusUtil.isTerminalBuildStatus(status));
    SetFinalBuildStatusRequest request =
        new SetFinalBuildStatusRequest()
            .setStampedeId(stampedeId)
            .setBuildStatus(status)
            .setBuildStatusMessage(statusMessage);
    FrontendRequest frontendRequest =
        new FrontendRequest()
            .setType(FrontendRequestType.SET_FINAL_BUILD_STATUS)
            .setSetFinalBuildStatusRequest(request);
    makeRequestChecked(frontendRequest);
  }

  @Override
  public void close() throws IOException {
    service.close();
  }

  public void setCoordinator(StampedeId stampedeId, int coordinatorPort, String coordinatorAddress)
      throws IOException {
    SetCoordinatorRequest request =
        new SetCoordinatorRequest()
            .setCoordinatorHostname(coordinatorAddress)
            .setCoordinatorPort(coordinatorPort)
            .setStampedeId(stampedeId);

    FrontendRequest frontendRequest = new FrontendRequest();
    frontendRequest.setType(FrontendRequestType.SET_COORDINATOR);
    frontendRequest.setSetCoordinatorRequest(request);

    FrontendResponse response = makeRequestChecked(frontendRequest);
    Preconditions.checkState(response.isSetSetCoordinatorResponse());
  }

  /**
   * Tells the frontend to schedule given number of minions
   *
   * @throws IOException
   */
  public void enqueueMinions(
      StampedeId stampedeId,
      String buildLabel,
      int totalNumberOfMinions,
      String minionQueueName,
      MinionType minionType,
      String minionRegion)
      throws IOException {
    EnqueueMinionsRequest request =
        new EnqueueMinionsRequest()
            .setMinionQueue(minionQueueName)
            .setNumberOfMinions(totalNumberOfMinions)
            .setMinionType(minionType)
            .setStampedeId(stampedeId)
            .setBuildLabel(buildLabel)
            .setRegion(minionRegion);

    FrontendRequest frontendRequest = new FrontendRequest();
    frontendRequest.setType(FrontendRequestType.ENQUEUE_MINIONS);
    frontendRequest.setEnqueueMinionsRequest(request);

    FrontendResponse response = makeRequestChecked(frontendRequest);
    Preconditions.checkState(response.isSetEnqueueMinionsResponse());
  }

  /** Reports that the coordinator is alive to the stampede servers. */
  public void reportCoordinatorIsAlive(StampedeId stampedeId) throws IOException {
    ReportCoordinatorAliveRequest request = new ReportCoordinatorAliveRequest();
    request.setStampedeId(stampedeId);

    FrontendRequest frontendRequest = new FrontendRequest();
    frontendRequest.setType(FrontendRequestType.REPORT_COORDINATOR_ALIVE);
    frontendRequest.setReportCoordinatorAliveRequest(request);
    FrontendResponse response = makeRequestChecked(frontendRequest);
    Preconditions.checkState(response.isSetReportCoordinatorAliveResponse());
  }

  private FrontendResponse makeRequestChecked(FrontendRequest request) throws IOException {
    FrontendResponse response = service.makeRequest(request);
    Preconditions.checkState(response.isSetWasSuccessful());
    if (!response.wasSuccessful) {
      throw new IOException(
          String.format(
              "Stampede request of type [%s] failed with error message [%s].",
              request.getType().toString(), response.getErrorMessage()));
    }
    Preconditions.checkState(request.isSetType());
    Preconditions.checkState(request.getType().equals(response.getType()));
    return response;
  }
}
