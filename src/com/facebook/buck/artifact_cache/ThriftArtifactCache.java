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
package com.facebook.buck.artifact_cache;

import com.facebook.buck.artifact_cache.thrift.ArtifactMetadata;
import com.facebook.buck.artifact_cache.thrift.BuckCacheDeleteRequest;
import com.facebook.buck.artifact_cache.thrift.BuckCacheDeleteResponse;
import com.facebook.buck.artifact_cache.thrift.BuckCacheFetchRequest;
import com.facebook.buck.artifact_cache.thrift.BuckCacheFetchResponse;
import com.facebook.buck.artifact_cache.thrift.BuckCacheMultiContainsRequest;
import com.facebook.buck.artifact_cache.thrift.BuckCacheMultiContainsResponse;
import com.facebook.buck.artifact_cache.thrift.BuckCacheMultiFetchRequest;
import com.facebook.buck.artifact_cache.thrift.BuckCacheMultiFetchResponse;
import com.facebook.buck.artifact_cache.thrift.BuckCacheRequest;
import com.facebook.buck.artifact_cache.thrift.BuckCacheRequestType;
import com.facebook.buck.artifact_cache.thrift.BuckCacheResponse;
import com.facebook.buck.artifact_cache.thrift.BuckCacheStoreRequest;
import com.facebook.buck.artifact_cache.thrift.ContainsResult;
import com.facebook.buck.artifact_cache.thrift.FetchResultType;
import com.facebook.buck.artifact_cache.thrift.PayloadInfo;
import com.facebook.buck.io.file.LazyPath;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.slb.HttpResponse;
import com.facebook.buck.slb.ThriftProtocol;
import com.facebook.buck.slb.ThriftUtil;
import com.facebook.buck.util.RichStream;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.ByteSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okio.BufferedSink;

/**
 * This is the Thrift protocol for the cache. The underlying channel is still HTTP but the payload
 * is Thrift. To learn a bit more about the protocol please look at ThriftArtifactCacheProtocol.
 */
public class ThriftArtifactCache extends AbstractNetworkCache {
  static final Logger LOG = Logger.get(ThriftArtifactCache.class);

  public static final MediaType HYBRID_THRIFT_STREAM_CONTENT_TYPE =
      MediaType.parse("application/x-hybrid-thrift-binary");
  public static final String PROTOCOL_HEADER = "X-Thrift-Protocol";
  public static final ThriftProtocol PROTOCOL = ThriftProtocol.COMPACT;

  private final String hybridThriftEndpoint;
  private final boolean distributedBuildModeEnabled;
  private final BuildId buildId;
  private final int multiFetchLimit;
  private final int concurrencyLevel;

  public ThriftArtifactCache(
      NetworkCacheArgs args,
      String hybridThriftEndpoint,
      boolean distributedBuildModeEnabled,
      BuildId buildId,
      int multiFetchLimit,
      int concurrencyLevel) {
    super(args);
    this.buildId = buildId;
    this.multiFetchLimit = multiFetchLimit;
    this.concurrencyLevel = concurrencyLevel;
    this.hybridThriftEndpoint = hybridThriftEndpoint;
    this.distributedBuildModeEnabled = distributedBuildModeEnabled;
  }

  @Override
  protected FetchResult fetchImpl(RuleKey ruleKey, LazyPath output) throws IOException {
    FetchResult.Builder resultBuilder = FetchResult.builder();

    BuckCacheFetchRequest fetchRequest = new BuckCacheFetchRequest();
    com.facebook.buck.artifact_cache.thrift.RuleKey thriftRuleKey =
        new com.facebook.buck.artifact_cache.thrift.RuleKey();
    thriftRuleKey.setHashString(ruleKey.getHashCode().toString());
    fetchRequest.setRuleKey(thriftRuleKey);
    fetchRequest.setRepository(getRepository());
    fetchRequest.setScheduleType(scheduleType);
    fetchRequest.setDistributedBuildModeEnabled(distributedBuildModeEnabled);

    BuckCacheRequest cacheRequest = newCacheRequest();
    cacheRequest.setType(BuckCacheRequestType.FETCH);
    cacheRequest.setFetchRequest(fetchRequest);

    LOG.verbose("Will fetch key %s", thriftRuleKey);

    ThriftArtifactCacheProtocol.Request request =
        ThriftArtifactCacheProtocol.createRequest(PROTOCOL, cacheRequest);
    Request.Builder builder = toOkHttpRequest(request);
    try (HttpResponse httpResponse = fetchClient.makeRequest(hybridThriftEndpoint, builder)) {
      if (httpResponse.statusCode() != 200) {
        String message =
            String.format(
                "Failed to fetch cache artifact with HTTP status code [%d:%s] "
                    + " to url [%s] for rule key [%s].",
                httpResponse.statusCode(),
                httpResponse.statusMessage(),
                httpResponse.requestUrl(),
                ruleKey.toString());
        LOG.warn(message);
        return resultBuilder
            .setCacheResult(CacheResult.error(getName(), getMode(), message))
            .build();
      }

      try (ThriftArtifactCacheProtocol.Response response =
          ThriftArtifactCacheProtocol.parseResponse(PROTOCOL, httpResponse.getBody())) {
        resultBuilder.setResponseSizeBytes(httpResponse.contentLength());

        BuckCacheResponse cacheResponse = response.getThriftData();
        if (!cacheResponse.isWasSuccessful()) {
          LOG.warn("Request was unsuccessful: %s", cacheResponse.getErrorMessage());
          return resultBuilder
              .setCacheResult(
                  CacheResult.error(getName(), getMode(), cacheResponse.getErrorMessage()))
              .build();
        }

        BuckCacheFetchResponse fetchResponse = cacheResponse.getFetchResponse();

        if (LOG.isDebugEnabled()) {
          LOG.debug(
              "Debug info for cache fetch request: request=[%s] response=[%s]",
              ThriftUtil.thriftToDebugJson(cacheRequest),
              ThriftUtil.thriftToDebugJson(cacheResponse));
        }

        if (!fetchResponse.isArtifactExists()) {
          LOG.verbose("Artifact did not exist.");
          return resultBuilder.setCacheResult(CacheResult.miss()).build();
        }

        LOG.verbose("Got artifact.  Attempting to read payload.");
        Path tmp = createTempFileForDownload();
        ThriftArtifactCacheProtocol.Response.ReadPayloadInfo readResult;
        try (OutputStream tmpFile = getProjectFilesystem().newFileOutputStream(tmp)) {
          try {
            readResult = response.readPayload(tmpFile);
          } catch (IOException e) {
            LOG.debug(e, "encountered an exception while receiving the payload for %s", ruleKey);
            throw e;
          }
          LOG.verbose("Successfully read payload: %d bytes.", readResult.getBytesRead());
        }

        if (!fetchResponse.isSetMetadata()) {
          String msg =
              String.format(
                  "ArtifactMetadata section is missing in the response. response=[%s]",
                  ThriftUtil.thriftToDebugJson(fetchResponse));
          return resultBuilder.setCacheResult(CacheResult.error(getName(), getMode(), msg)).build();
        }
        ArtifactMetadata metadata = fetchResponse.getMetadata();
        if (LOG.isVerboseEnabled()) {
          LOG.verbose(
              String.format(
                  "Fetched artifact with rule key [%s] contains the following metadata: [%s].",
                  ruleKey, ThriftUtil.thriftToDebugJson(metadata)));
        }

        if (!metadata.isSetRuleKeys()) {
          return resultBuilder
              .setCacheResult(
                  CacheResult.error(
                      getName(), getMode(), "Rule key section in the metadata is not set."))
              .build();
        }
        ImmutableSet<RuleKey> associatedRuleKeys = null;
        try {
          associatedRuleKeys = toImmutableSet(metadata.getRuleKeys());
        } catch (IllegalArgumentException e) {
          String msg =
              String.format(
                  "Exception parsing the rule keys in the metadata section [%s] with exception [%s].",
                  ThriftUtil.thriftToDebugJson(metadata), e.toString());
          return resultBuilder.setCacheResult(CacheResult.error(getName(), getMode(), msg)).build();
        }

        resultBuilder
            .setBuildTarget(Optional.ofNullable(metadata.getBuildTarget()))
            .setAssociatedRuleKeys(associatedRuleKeys)
            .setArtifactSizeBytes(readResult.getBytesRead());
        if (!metadata.isSetArtifactPayloadMd5()) {
          String msg = "Fetched artifact is missing the MD5 hash.";
          LOG.warn(msg);
        } else {
          resultBuilder.setArtifactContentHash(metadata.getArtifactPayloadMd5());
          if (!readResult
              .getMd5Hash()
              .equals(fetchResponse.getMetadata().getArtifactPayloadMd5())) {
            String msg =
                String.format(
                    "The artifact fetched from cache is corrupted. ExpectedMD5=[%s] ActualMD5=[%s]",
                    fetchResponse.getMetadata().getArtifactPayloadMd5(), readResult.getMd5Hash());
            LOG.warn(msg);
            return resultBuilder
                .setCacheResult(CacheResult.error(getName(), getMode(), msg))
                .build();
          }
        }

        // This makes sure we don't have 'half downloaded files' in the dir cache.
        getProjectFilesystem().move(tmp, output.get(), StandardCopyOption.REPLACE_EXISTING);
        return resultBuilder
            .setCacheResult(
                CacheResult.hit(
                    getName(),
                    getMode(),
                    ImmutableMap.copyOf(fetchResponse.getMetadata().getMetadata()),
                    readResult.getBytesRead()))
            .build();
      }
    }
  }

  private BuckCacheRequest newCacheRequest() {
    BuckCacheRequest buckCacheRequest = new BuckCacheRequest();
    buckCacheRequest.setBuckBuildId(buildId.toString());
    return buckCacheRequest;
  }

  private void processContainsResult(
      RuleKey ruleKey, ContainsResult containsResult, MultiContainsResult.Builder resultBuilder) {
    switch (containsResult.resultType) {
      case CONTAINS:
        resultBuilder.putCacheResults(ruleKey, CacheResult.contains(getName(), getMode()));
        break;
      case DOES_NOT_CONTAIN:
        resultBuilder.putCacheResults(ruleKey, CacheResult.miss());
        break;
      case UNKNOWN_DUE_TO_TRANSIENT_ERRORS:
        resultBuilder.putCacheResults(
            ruleKey,
            CacheResult.error(
                getName(),
                getMode(),
                String.format(
                    "Multi-contains request reported transient errors in some backing stores for "
                        + "rule key [%s]. Artifact may still be present.",
                    ruleKey.toString())));
        break;
    }
  }

  private MultiContainsResult processMultiContainsHttpResponse(
      HttpResponse httpResponse, List<RuleKey> ruleKeys) throws IOException {
    MultiContainsResult.Builder resultBuilder = MultiContainsResult.builder();

    if (httpResponse.statusCode() != 200) {
      LOG.warn(
          String.format(
              "Failed to multi-contains request for [%d] cache artifacts "
                  + "with HTTP status code [%d:%s] to url [%s].",
              ruleKeys.size(),
              httpResponse.statusCode(),
              httpResponse.statusMessage(),
              httpResponse.requestUrl()));

      Function<RuleKey, CacheResult> genErrorResult =
          ruleKey ->
              CacheResult.error(
                  getName(),
                  getMode(),
                  String.format(
                      "Failed to check cache artifact (via multi-contains request) "
                          + "with HTTP status code [%d:%s] to url [%s] for rule key [%s].",
                      httpResponse.statusCode(),
                      httpResponse.statusMessage(),
                      httpResponse.requestUrl(),
                      ruleKey.toString()));

      return resultBuilder.setCacheResults(Maps.toMap(ruleKeys, genErrorResult::apply)).build();
    }

    try (ThriftArtifactCacheProtocol.Response response =
        ThriftArtifactCacheProtocol.parseResponse(PROTOCOL, httpResponse.getBody())) {
      resultBuilder.setResponseSizeBytes(httpResponse.contentLength());

      BuckCacheResponse cacheResponse = response.getThriftData();
      if (!cacheResponse.isWasSuccessful()) {
        LOG.warn("Request was unsuccessful: %s", cacheResponse.getErrorMessage());
        Function<RuleKey, CacheResult> genErrorResult =
            k -> CacheResult.error(getName(), getMode(), cacheResponse.getErrorMessage());
        return resultBuilder.setCacheResults(Maps.toMap(ruleKeys, genErrorResult::apply)).build();
      }

      BuckCacheMultiContainsResponse containsResponse = cacheResponse.getMultiContainsResponse();

      Preconditions.checkState(
          containsResponse.results.size() == ruleKeys.size(),
          "Bad response from server. MultiContains Response does not have the "
              + "same number of results as the MultiContains Request.");

      for (int i = 0; i < containsResponse.results.size(); ++i) {
        processContainsResult(ruleKeys.get(i), containsResponse.results.get(i), resultBuilder);
      }
      return resultBuilder.build();
    }
  }

  @Override
  protected MultiContainsResult multiContainsImpl(ImmutableSet<RuleKey> uniqueRuleKeys)
      throws IOException {
    List<RuleKey> ruleKeys = Lists.newArrayList(uniqueRuleKeys);

    BuckCacheMultiContainsRequest containsRequest = new BuckCacheMultiContainsRequest();
    containsRequest.setRuleKeys(new ArrayList<>(ruleKeys.size()));

    for (RuleKey ruleKey : ruleKeys) {
      com.facebook.buck.artifact_cache.thrift.RuleKey thriftRuleKey =
          new com.facebook.buck.artifact_cache.thrift.RuleKey();
      thriftRuleKey.setHashString(ruleKey.getHashCode().toString());
      containsRequest.addToRuleKeys(thriftRuleKey);
    }

    LOG.verbose(
        "Will check (multi-contains) for keys [%s]",
        Joiner.on(",").join(containsRequest.getRuleKeys()));

    containsRequest.setRepository(getRepository());
    containsRequest.setScheduleType(scheduleType);
    containsRequest.setDistributedBuildModeEnabled(distributedBuildModeEnabled);

    BuckCacheRequest cacheRequest = newCacheRequest();
    cacheRequest.setType(BuckCacheRequestType.CONTAINS);
    cacheRequest.setMultiContainsRequest(containsRequest);

    ThriftArtifactCacheProtocol.Request request =
        ThriftArtifactCacheProtocol.createRequest(PROTOCOL, cacheRequest);
    Request.Builder builder = toOkHttpRequest(request);
    try (HttpResponse httpResponse = fetchClient.makeRequest(hybridThriftEndpoint, builder)) {
      MultiContainsResult result = processMultiContainsHttpResponse(httpResponse, ruleKeys);
      LOG.verbose(
          "(%d out of %d) artifacts exist in cache.",
          result.getCacheResults().values().stream().filter(r -> r.getType().isSuccess()).count(),
          ruleKeys.size());
      return result;
    }
  }

  @Override
  protected int getMultiFetchBatchSize(int pendingRequestsSize) {
    if (concurrencyLevel > 0)
      return Math.min(multiFetchLimit, 1 + pendingRequestsSize / concurrencyLevel);
    return 0;
  }

  @Override
  protected MultiFetchResult multiFetchImpl(Iterable<FetchRequest> requests) throws IOException {
    ImmutableList<RuleKey> keys =
        RichStream.from(requests)
            .map(FetchRequest::getRuleKey)
            .collect(ImmutableList.toImmutableList());
    ImmutableList<LazyPath> outputs =
        RichStream.from(requests)
            .map(FetchRequest::getOutput)
            .collect(ImmutableList.toImmutableList());
    Preconditions.checkState(keys.size() == outputs.size());
    String joinedKeys = Joiner.on(", ").join(keys);
    LOG.verbose("Will fetch keys <%s>", joinedKeys);

    BuckCacheRequest cacheRequest = createMultiFetchRequest(keys);
    try (HttpResponse httpResponse =
        fetchClient.makeRequest(
            hybridThriftEndpoint,
            toOkHttpRequest(ThriftArtifactCacheProtocol.createRequest(PROTOCOL, cacheRequest)))) {
      return MultiFetchResult.of(
          processMultiFetchResponse(keys, outputs, cacheRequest, joinedKeys, httpResponse));
    }
  }

  com.facebook.buck.artifact_cache.thrift.RuleKey toThriftRuleKey(RuleKey ruleKey) {
    com.facebook.buck.artifact_cache.thrift.RuleKey thriftRuleKey =
        new com.facebook.buck.artifact_cache.thrift.RuleKey();
    thriftRuleKey.setHashString(ruleKey.getHashCode().toString());
    return thriftRuleKey;
  }

  private BuckCacheRequest createMultiFetchRequest(ImmutableList<RuleKey> keys) {
    BuckCacheMultiFetchRequest multiFetchRequest = new BuckCacheMultiFetchRequest();
    multiFetchRequest.setRepository(getRepository());
    multiFetchRequest.setScheduleType(scheduleType);
    multiFetchRequest.setDistributedBuildModeEnabled(distributedBuildModeEnabled);
    keys.forEach(k -> multiFetchRequest.addToRuleKeys(toThriftRuleKey(k)));

    BuckCacheRequest cacheRequest = newCacheRequest();
    cacheRequest.setType(BuckCacheRequestType.MULTI_FETCH);
    cacheRequest.setMultiFetchRequest(multiFetchRequest);
    return cacheRequest;
  }

  private Iterable<FetchResult> processMultiFetchResponse(
      ImmutableList<RuleKey> keys,
      ImmutableList<LazyPath> outputs,
      BuckCacheRequest cacheRequest,
      String joinedKeys,
      HttpResponse httpResponse)
      throws IOException {

    if (httpResponse.statusCode() != 200) {
      String message =
          String.format(
              "Failed to fetch cache artifact with HTTP status code [%d:%s] "
                  + " to url [%s] for rule keys [%s].",
              httpResponse.statusCode(),
              httpResponse.statusMessage(),
              httpResponse.requestUrl(),
              joinedKeys);
      LOG.warn(message);
      CacheResult cacheResult = CacheResult.error(getName(), getMode(), message);
      return keys.stream().map(k -> FetchResult.builder().setCacheResult(cacheResult).build())
          ::iterator;
    }

    try (ThriftArtifactCacheProtocol.Response response =
        ThriftArtifactCacheProtocol.parseResponse(PROTOCOL, httpResponse.getBody())) {
      return convertMultiFetchResponseToFetchResults(
                  keys, outputs, cacheRequest, httpResponse, response)
              .stream()
              .map(b -> b.build())
          ::iterator;
    }
  }

  private ImmutableList<FetchResult.Builder> convertMultiFetchResponseToFetchResults(
      ImmutableList<RuleKey> keys,
      ImmutableList<LazyPath> outputs,
      BuckCacheRequest cacheRequest,
      HttpResponse httpResponse,
      ThriftArtifactCacheProtocol.Response response)
      throws IOException {
    long responseSizeBytes = httpResponse.contentLength();
    ImmutableList<FetchResult.Builder> resultsBuilders =
        keys.stream().map(k -> FetchResult.builder()).collect(ImmutableList.toImmutableList());

    BuckCacheResponse cacheResponse = response.getThriftData();
    resultsBuilders.forEach(b -> b.setResponseSizeBytes(responseSizeBytes));

    if (!cacheResponse.isWasSuccessful()) {
      String message = cacheResponse.getErrorMessage();
      LOG.warn("Request was unsuccessful: %s", message);
      resultsBuilders.forEach(
          b -> b.setCacheResult(CacheResult.error(getName(), getMode(), message)));
      return resultsBuilders;
    }

    if (LOG.isDebugEnabled()) {
      LOG.debug(
          "Debug info for cache multiFetch request: request=[%s] response=[%s]",
          ThriftUtil.thriftToDebugJson(cacheRequest), ThriftUtil.thriftToDebugJson(cacheResponse));
    }

    BuckCacheMultiFetchResponse multiFetchResponse = cacheResponse.getMultiFetchResponse();
    if (multiFetchResponse.getResultsSize() != keys.size()) {
      String message =
          String.format(
              "Response had incorrect results size expected=%d actual=%d",
              keys.size(), multiFetchResponse.getResultsSize());
      LOG.warn(message);
      resultsBuilders.forEach(
          b -> b.setCacheResult(CacheResult.error(getName(), getMode(), message)));
      return resultsBuilders;
    }

    for (int i = 0; i < keys.size(); i++) {
      RuleKey ruleKey = keys.get(i);
      com.facebook.buck.artifact_cache.thrift.FetchResult fetchResponse =
          multiFetchResponse.getResults().get(i);
      FetchResult.Builder builder = resultsBuilders.get(i);
      LOG.verbose("Handling result for key %s", ruleKey);

      convertSingleMultiFetchResult(
          fetchResponse, ruleKey, new PayloadReader(response), outputs.get(i), builder);
    }
    return resultsBuilders;
  }

  private void convertSingleMultiFetchResult(
      com.facebook.buck.artifact_cache.thrift.FetchResult fetchResponse,
      RuleKey ruleKey,
      PayloadReader payloadReader,
      LazyPath output,
      FetchResult.Builder builder)
      throws IOException {
    FetchResultType resultType = fetchResponse.getResultType();
    switch (resultType) {
      case UNKNOWN:
      case ERROR:
        builder.setCacheResult(
            CacheResult.error(
                getName(), getMode(), String.format("Got bad result of type %s", resultType)));
        return;
      case MISS:
        LOG.verbose("Artifact did not exist.");
        builder.setCacheResult(CacheResult.miss());
        return;
      case CONTAINS:
        LOG.verbose("Got CONTAINS result.");
        builder.setCacheResult(CacheResult.skipped());
        return;
      case SKIPPED:
        LOG.verbose("Got SKIPPED result.");
        builder.setCacheResult(CacheResult.skipped());
        return;
      case HIT:
        // Handled below.
        break;
    }

    if (resultType != FetchResultType.HIT) {
      String message = String.format("Got unexpected result type: %s", resultType);
      LOG.verbose(message);
      // We need to throw an exception in this case. We don't know if this result type has a
      // payload or not and so we could start assigning payloads to incorrect results.
      // TODO(cjhopman): Do we have to the same for the UNKNOWN case above?
      throw new IOException(message);
    }

    LOG.verbose("Got artifact.  Attempting to read payload.");
    Path tmp = createTempFileForDownload();

    // Always read payload even if information is missing to ensure that we associate payloads
    // with the correct result.
    @SuppressWarnings("PMD.PrematureDeclaration")
    ThriftArtifactCacheProtocol.Response.ReadPayloadInfo readResult =
        payloadReader.readNextPayload(tmp, ruleKey);

    if (!fetchResponse.isSetMetadata()) {
      String msg =
          String.format(
              "ArtifactMetadata section is missing in the response. response=[%s]",
              ThriftUtil.thriftToDebugJson(fetchResponse));
      LOG.verbose(msg);
      builder.setCacheResult(CacheResult.error(getName(), getMode(), msg));
      return;
    }

    ArtifactMetadata metadata = fetchResponse.getMetadata();
    if (LOG.isVerboseEnabled()) {
      LOG.verbose(
          String.format(
              "Fetched artifact with rule key [%s] contains the following metadata: [%s].",
              ruleKey, ThriftUtil.thriftToDebugJson(metadata)));
    }

    if (!metadata.isSetRuleKeys()) {
      String msg = "Rule key section in the metadata is not set.";
      LOG.verbose(msg);
      builder.setCacheResult(CacheResult.error(getName(), getMode(), msg));
      return;
    }

    ImmutableSet<RuleKey> associatedRuleKeys;
    try {
      associatedRuleKeys = toImmutableSet(metadata.getRuleKeys());
    } catch (IllegalArgumentException e) {
      String msg =
          String.format(
              "Exception parsing the rule keys in the metadata section [%s] with exception [%s].",
              ThriftUtil.thriftToDebugJson(metadata), e.toString());
      LOG.verbose(msg);
      builder.setCacheResult(CacheResult.error(getName(), getMode(), msg));
      return;
    }

    builder
        .setBuildTarget(Optional.ofNullable(metadata.getBuildTarget()))
        .setAssociatedRuleKeys(associatedRuleKeys)
        .setArtifactSizeBytes(readResult.getBytesRead());

    // Unlike fetch(), multiFetch() requires that the md5 be set. This is to provide extra
    // protection against assigning a payload to the incorrect request.
    if (!metadata.isSetArtifactPayloadMd5()) {
      throw new IOException(
          String.format(
              "Metadata for rulekey %s does not contain a payload md5. "
                  + "Rejecting entire multiFetch result.",
              ruleKey));
    }

    builder.setArtifactContentHash(metadata.getArtifactPayloadMd5());
    if (!readResult.getMd5Hash().equals(fetchResponse.getMetadata().getArtifactPayloadMd5())) {
      String msg =
          String.format(
              "The artifact fetched from cache is corrupted. ExpectedMD5=[%s] ActualMD5=[%s]",
              fetchResponse.getMetadata().getArtifactPayloadMd5(), readResult.getMd5Hash());
      LOG.warn(msg);
      builder.setCacheResult(CacheResult.error(getName(), getMode(), msg));
      return;
    }

    // This makes sure we don't have 'half downloaded files' in the dir cache.
    getProjectFilesystem().move(tmp, output.get(), StandardCopyOption.REPLACE_EXISTING);
    builder.setCacheResult(
        CacheResult.hit(
            getName(),
            getMode(),
            ImmutableMap.copyOf(fetchResponse.getMetadata().getMetadata()),
            readResult.getBytesRead()));
  }

  private static ImmutableSet<RuleKey> toImmutableSet(
      List<com.facebook.buck.artifact_cache.thrift.RuleKey> ruleKeys) {
    return ImmutableSet.copyOf(
        Iterables.transform(ruleKeys, input -> new RuleKey(input.getHashString())));
  }

  @Override
  protected StoreResult storeImpl(ArtifactInfo info, Path file) throws IOException {
    StoreResult.Builder resultBuilder = StoreResult.builder();
    ByteSource artifact =
        new ByteSource() {
          @Override
          public InputStream openStream() throws IOException {
            return getProjectFilesystem().newFileInputStream(file);
          }
        };

    BuckCacheStoreRequest storeRequest = new BuckCacheStoreRequest();
    ArtifactMetadata artifactMetadata =
        infoToMetadata(info, artifact, getRepository(), scheduleType, distributedBuildModeEnabled);
    storeRequest.setMetadata(artifactMetadata);
    PayloadInfo payloadInfo = new PayloadInfo();
    long artifactSizeBytes = artifact.size();
    payloadInfo.setSizeBytes(artifactSizeBytes);
    BuckCacheRequest cacheRequest = newCacheRequest();
    cacheRequest.addToPayloads(payloadInfo);
    cacheRequest.setType(BuckCacheRequestType.STORE);
    cacheRequest.setStoreRequest(storeRequest);

    if (LOG.isVerboseEnabled()) {
      LOG.verbose(
          String.format(
              "Storing artifact with metadata: [%s].",
              ThriftUtil.thriftToDebugJson(artifactMetadata)));
    }

    ThriftArtifactCacheProtocol.Request request =
        ThriftArtifactCacheProtocol.createRequest(PROTOCOL, cacheRequest, artifact);
    Request.Builder builder = toOkHttpRequest(request);
    resultBuilder.setRequestSizeBytes(request.getRequestLengthBytes());
    try (HttpResponse httpResponse = storeClient.makeRequest(hybridThriftEndpoint, builder)) {
      if (httpResponse.statusCode() != 200) {
        throw new IOException(
            String.format(
                "Failed to store cache artifact with HTTP status code [%d:%s] "
                    + " to url [%s] for build target [%s] that has size [%d] bytes.",
                httpResponse.statusCode(),
                httpResponse.statusMessage(),
                httpResponse.requestUrl(),
                info.getBuildTarget().orElse(null),
                artifactSizeBytes));
      }

      try (ThriftArtifactCacheProtocol.Response response =
          ThriftArtifactCacheProtocol.parseResponse(PROTOCOL, httpResponse.getBody())) {
        BuckCacheResponse cacheResponse = response.getThriftData();
        if (!cacheResponse.isWasSuccessful()) {
          reportFailureWithFormatKey(
              "Failed to store artifact with thriftErrorMessage=[%s] "
                  + "url=[%s] artifactSizeBytes=[%d]",
              response.getThriftData().getErrorMessage(),
              httpResponse.requestUrl(),
              artifactSizeBytes);
        }

        resultBuilder.setArtifactContentHash(storeRequest.getMetadata().artifactPayloadMd5);
        resultBuilder.setWasStoreSuccessful(cacheResponse.isWasSuccessful());

        if (LOG.isDebugEnabled()) {
          LOG.debug(
              "Debug info for cache store request: artifactMetadata=[%s] response=[%s]",
              ThriftUtil.thriftToDebugJson(artifactMetadata),
              ThriftUtil.thriftToDebugJson(cacheResponse));
        }
      }
    }
    return resultBuilder.build();
  }

  private Path createTempFileForDownload() throws IOException {
    getProjectFilesystem().mkdirs(getProjectFilesystem().getBuckPaths().getScratchDir());
    return getProjectFilesystem()
        .createTempFile(
            getProjectFilesystem().getBuckPaths().getScratchDir(), "buckcache_artifact", ".tmp");
  }

  private static ArtifactMetadata infoToMetadata(
      ArtifactInfo info,
      ByteSource file,
      String repository,
      String scheduleType,
      boolean distributedBuildModeEnabled)
      throws IOException {
    ArtifactMetadata metadata = new ArtifactMetadata();
    if (info.getBuildTarget().isPresent()) {
      metadata.setBuildTarget(info.getBuildTarget().get().toString());
    }

    metadata.setRuleKeys(
        ImmutableList.copyOf(
            Iterables.transform(
                info.getRuleKeys(),
                input -> {
                  com.facebook.buck.artifact_cache.thrift.RuleKey ruleKey =
                      new com.facebook.buck.artifact_cache.thrift.RuleKey();
                  ruleKey.setHashString(input.getHashCode().toString());
                  return ruleKey;
                })));

    metadata.setMetadata(info.getMetadata());
    metadata.setArtifactPayloadMd5(ThriftArtifactCacheProtocol.computeMd5Hash(file));
    metadata.setRepository(repository);
    metadata.setScheduleType(scheduleType);
    metadata.setDistributedBuildModeEnabled(distributedBuildModeEnabled);

    return metadata;
  }

  private static Request.Builder toOkHttpRequest(ThriftArtifactCacheProtocol.Request request) {
    Request.Builder builder =
        new Request.Builder().addHeader(PROTOCOL_HEADER, PROTOCOL.toString().toLowerCase());
    builder.post(
        new RequestBody() {
          @Override
          public MediaType contentType() {
            return HYBRID_THRIFT_STREAM_CONTENT_TYPE;
          }

          @Override
          public long contentLength() {
            return request.getRequestLengthBytes();
          }

          @Override
          public void writeTo(BufferedSink bufferedSink) throws IOException {
            request.writeAndClose(bufferedSink.outputStream());
          }
        });

    return builder;
  }

  @Override
  protected CacheDeleteResult deleteImpl(List<RuleKey> ruleKeys) throws IOException {
    List<com.facebook.buck.artifact_cache.thrift.RuleKey> ruleKeysThrift =
        ruleKeys
            .stream()
            .map(
                r ->
                    new com.facebook.buck.artifact_cache.thrift.RuleKey()
                        .setHashString(r.toString()))
            .collect(Collectors.toList());

    BuckCacheDeleteRequest deleteRequest = new BuckCacheDeleteRequest();
    deleteRequest.setDistributedBuildModeEnabledIsSet(distributedBuildModeEnabled);
    deleteRequest.setRepository(getRepository());
    deleteRequest.setRuleKeys(ruleKeysThrift);
    deleteRequest.setScheduleType(scheduleType);
    BuckCacheRequest cacheRequest = newCacheRequest();
    cacheRequest.setType(BuckCacheRequestType.DELETE_REQUEST);
    cacheRequest.setDeleteRequest(deleteRequest);

    if (LOG.isVerboseEnabled()) {
      LOG.verbose(String.format("Deleting rule keys: [%s].", ruleKeys));
    }

    ThriftArtifactCacheProtocol.Request request =
        ThriftArtifactCacheProtocol.createRequest(PROTOCOL, cacheRequest);
    Request.Builder builder = toOkHttpRequest(request);
    try (HttpResponse httpResponse = storeClient.makeRequest(hybridThriftEndpoint, builder)) {
      if (httpResponse.statusCode() != 200) {
        throw new IOException(
            String.format(
                "Failed to delete cache artifacts with HTTP status code [%d:%s] " + " to url [%s].",
                httpResponse.statusCode(),
                httpResponse.statusMessage(),
                httpResponse.requestUrl()));
      }

      try (ThriftArtifactCacheProtocol.Response response =
          ThriftArtifactCacheProtocol.parseResponse(PROTOCOL, httpResponse.getBody())) {
        BuckCacheResponse cacheResponse = response.getThriftData();
        if (!cacheResponse.isWasSuccessful()) {
          throw new IOException(
              String.format(
                  "Failed to store artifact with thriftErrorMessage=[%s] " + "url=[%s]",
                  response.getThriftData().getErrorMessage(), httpResponse.requestUrl()));
        }
        if (LOG.isDebugEnabled()) {
          LOG.debug(
              "Debug info for cache delete request: ruleKeys=[%s] response=[%s]",
              ruleKeys, ThriftUtil.thriftToDebugJson(cacheResponse));
        }

        BuckCacheDeleteResponse deleteResponse = cacheResponse.deleteResponse;
        if (deleteResponse == null) {
          throw new IOException("Got response without deleteResponse object");
        }

        String storesCommaSeparated;
        if (deleteResponse.debugInfo != null
            && deleteResponse.debugInfo.storesDeletedFrom != null) {
          storesCommaSeparated = String.join(", ", deleteResponse.debugInfo.storesDeletedFrom);
        } else {
          storesCommaSeparated = "";
        }

        String cacheName =
            ThriftArtifactCache.class.getSimpleName() + "[" + storesCommaSeparated + "]";
        ImmutableList<String> cacheNames = ImmutableList.of(cacheName);
        return CacheDeleteResult.builder().setCacheNames(cacheNames).build();
      }
    }
  }

  private class PayloadReader {
    private ThriftArtifactCacheProtocol.Response response;

    private PayloadReader(ThriftArtifactCacheProtocol.Response response) {
      this.response = response;
    }

    ThriftArtifactCacheProtocol.Response.ReadPayloadInfo readNextPayload(Path path, RuleKey ruleKey)
        throws IOException {
      try (OutputStream tmpFile = getProjectFilesystem().newFileOutputStream(path)) {
        ThriftArtifactCacheProtocol.Response.ReadPayloadInfo result;
        try {
          result = response.readPayload(tmpFile);
        } catch (IOException e) {
          LOG.debug(e, "Encountered an exception while receiving the payload for %s", ruleKey);
          throw e;
        }
        LOG.verbose("Successfully read payload: %d bytes.", result.getBytesRead());
        return result;
      }
    }
  }
}
