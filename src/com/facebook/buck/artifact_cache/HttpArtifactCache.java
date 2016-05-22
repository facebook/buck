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

package com.facebook.buck.artifact_cache;

import com.facebook.buck.artifact_cache.HttpArtifactCacheEvent.Finished;
import com.facebook.buck.artifact_cache.HttpArtifactCacheEvent.Started;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.BorrowablePath;
import com.facebook.buck.io.LazyPath;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.slb.HttpResponse;
import com.facebook.buck.slb.HttpService;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.io.ByteSource;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.regex.Matcher;
import java.util.Set;

import okio.BufferedSink;

public class HttpArtifactCache implements ArtifactCache {

  /**
   * If the user is offline, then we do not want to print every connection failure that occurs.
   * However, in practice, it appears that some connection failures can be intermittent, so we
   * should print enough to provide a signal of how flaky the connection is.
   */
  private static final Logger LOGGER = Logger.get(HttpArtifactCache.class);
  private static final MediaType OCTET_STREAM = MediaType.parse("application/octet-stream");

  private final String name;
  private final HttpService fetchClient;
  private final HttpService storeClient;
  private final boolean doStore;
  private final ProjectFilesystem projectFilesystem;
  private final BuckEventBus buckEventBus;
  private final ListeningExecutorService httpWriteExecutorService;
  private final String errorTextTemplate;
  private final Optional<Long> maxStoreSize;

  private final Set<String> seenErrors = Sets.newConcurrentHashSet();

  public HttpArtifactCache(
      String name,
      HttpService fetchClient,
      HttpService storeClient,
      boolean doStore,
      ProjectFilesystem projectFilesystem,
      BuckEventBus buckEventBus,
      ListeningExecutorService httpWriteExecutorService,
      String errorTextTemplate,
      Optional<Long> maxStoreSize) {
    this.name = name;
    this.fetchClient = fetchClient;
    this.storeClient = storeClient;
    this.doStore = doStore;
    this.projectFilesystem = projectFilesystem;
    this.buckEventBus = buckEventBus;
    this.httpWriteExecutorService = httpWriteExecutorService;
    this.errorTextTemplate = errorTextTemplate;
    this.maxStoreSize = maxStoreSize;
  }

  protected HttpResponse fetchCall(String path, Request.Builder requestBuilder) throws IOException {
    return fetchClient.makeRequest(path, requestBuilder);
  }

  public CacheResult fetchImpl(
      RuleKey ruleKey,
      LazyPath output,
      final Finished.Builder eventBuilder) throws IOException {

    Request.Builder requestBuilder =
        new Request.Builder()
            .get();
    try (HttpResponse response = fetchCall(
        "/artifacts/key/" + ruleKey.toString(),
        requestBuilder)) {
      eventBuilder.setResponseSizeBytes(response.contentLength());

      try (DataInputStream input =
               new DataInputStream(new FullyReadOnCloseInputStream(response.getBody()))) {

        if (response.code() == HttpURLConnection.HTTP_NOT_FOUND) {
          LOGGER.info("fetch(%s, %s): cache miss", response.requestUrl(), ruleKey);
          return CacheResult.miss();
        }

        if (response.code() != HttpURLConnection.HTTP_OK) {
          String msg = String.format("unexpected response: %d", response.code());
          reportFailure("fetch(%s, %s): %s", response.requestUrl(), ruleKey, msg);
          eventBuilder.setErrorMessage(msg);
          return CacheResult.error(name, msg);
        }

        // Setup a temporary file, which sits next to the destination, to write to and
        // make sure all parent dirs exist.
        Path file = output.get();
        projectFilesystem.createParentDirs(file);
        Path temp = projectFilesystem.createTempFile(
            file.getParent(),
            file.getFileName().toString(),
            ".tmp");

        FetchResponseReadResult fetchedData;
        try (OutputStream tempFileOutputStream = projectFilesystem.newFileOutputStream(temp)) {
          fetchedData = HttpArtifactCacheBinaryProtocol.readFetchResponse(
              input,
              tempFileOutputStream);
        }

        eventBuilder.setResponseSizeBytes(fetchedData.getResponseSizeBytes());
        eventBuilder.setArtifactContentHash(fetchedData.getArtifactOnlyHashCode().toString());

        // Verify that we were one of the rule keys that stored this artifact.
        if (!fetchedData.getRuleKeys().contains(ruleKey)) {
          String msg = "incorrect key name";
          reportFailure("fetch(%s, %s): %s", response.requestUrl(), ruleKey, msg);
          eventBuilder.setErrorMessage(msg);
          return CacheResult.error(name, msg);
        }

        // Now form the checksum on the file we got and compare it to the checksum form the
        // the HTTP header.  If it's incorrect, log this and return a miss.
        if (!fetchedData.getExpectedHashCode().equals(fetchedData.getActualHashCode())) {
          String msg = "artifact had invalid checksum";
          reportFailure("fetch(%s, %s): %s", response.requestUrl(), ruleKey, msg);
          projectFilesystem.deleteFileAtPath(temp);
          eventBuilder.setErrorMessage(msg);
          return CacheResult.error(name, msg);
        }

        // Finally, move the temp file into it's final place.
        projectFilesystem.move(temp, file, StandardCopyOption.REPLACE_EXISTING);

        LOGGER.info("fetch(%s, %s): cache hit", response.requestUrl(), ruleKey);
        return CacheResult.hit(name, fetchedData.getMetadata(), fetchedData.getResponseSizeBytes());
      }
    }
  }

  @Override
  public CacheResult fetch(RuleKey ruleKey, LazyPath output) {
    Started startedEvent = HttpArtifactCacheEvent.newFetchStartedEvent(ImmutableSet.<RuleKey>of());
    buckEventBus.post(startedEvent);
    Finished.Builder eventBuilder = HttpArtifactCacheEvent.newFinishedEventBuilder(startedEvent)
        .setRuleKeys(Lists.newArrayList(ruleKey));

    try {
      CacheResult result = fetchImpl(ruleKey, output, eventBuilder);
      buckEventBus.post(
          eventBuilder
              .setFetchResult(result)
              .build());
      return result;
    } catch (IOException e) {
      String msg = String.format("%s: %s", e.getClass().getName(), e.getMessage());
      reportFailure(e, "fetch(%s): %s", ruleKey, msg);
      CacheResult cacheResult = CacheResult.error(name, msg);
      buckEventBus.post(eventBuilder
          .setFetchResult(cacheResult)
          .setErrorMessage(msg)
          .build());
      return cacheResult;
    }
  }

  protected HttpResponse storeCall(Request.Builder requestBuilder) throws IOException {
    return storeClient.makeRequest("/artifacts/key", requestBuilder);
  }

  protected void storeImpl(
      ImmutableSet<RuleKey> ruleKeys,
      final ImmutableMap<String, String> metadata,
      final Path file,
      final Finished.Builder eventBuilder)
      throws IOException {

    if (maxStoreSize.isPresent() &&
        projectFilesystem.getFileSize(file) > maxStoreSize.get()) {
      return;
    }

    // Build the request, hitting the multi-key endpoint.
    Request.Builder builder = new Request.Builder();
    final HttpArtifactCacheBinaryProtocol.StoreRequest storeRequest =
        new HttpArtifactCacheBinaryProtocol.StoreRequest(
            ruleKeys,
            metadata,
            new ByteSource() {
              @Override
              public InputStream openStream() throws IOException {
                return projectFilesystem.newFileInputStream(file);
              }
            });

    eventBuilder.setRequestSizeBytes(storeRequest.getContentLength());

    // Wrap the file into a `RequestBody` which uses `ProjectFilesystem`.
    builder.put(
        new RequestBody() {
          @Override
          public MediaType contentType() {
            return OCTET_STREAM;
          }

          @Override
          public long contentLength() throws IOException {
            return storeRequest.getContentLength();
          }

          @Override
          public void writeTo(BufferedSink bufferedSink) throws IOException {
            StoreWriteResult writeResult = storeRequest.write(bufferedSink.outputStream());
            eventBuilder.setArtifactSizeBytes(writeResult.getArtifactSizeBytes());
            eventBuilder.setArtifactContentHash(
                writeResult.getArtifactContentHashCode().toString());
          }
        });

    // Dispatch the store operation and verify it succeeded.
    try (HttpResponse response = storeCall(builder)) {
      final boolean requestFailed = response.code() != HttpURLConnection.HTTP_ACCEPTED;
      if (requestFailed) {
        reportFailure(
            "store(%s, %s): unexpected response: %d",
            response.requestUrl(),
            ruleKeys,
            response.code());
      }

      eventBuilder.setWasUploadSuccessful(!requestFailed);
    }
  }

  @Override
  public ListenableFuture<Void> store(
      final ImmutableSet<RuleKey> ruleKeys,
      final ImmutableMap<String, String> metadata,
      final BorrowablePath output) {
    if (!isStoreSupported()) {
      return Futures.immediateFuture(null);
    }

    final HttpArtifactCacheEvent.Scheduled scheduled =
        HttpArtifactCacheEvent.newStoreScheduledEvent(
            ArtifactCacheEvent.getTarget(metadata), ruleKeys);
    buckEventBus.post(scheduled);

    final Path tmp;
    try {
      tmp = getPathForArtifact(output);
    } catch (IOException e) {
      LOGGER.error(e, "Failed to store artifact in temp file: " + output.getPath().toString());
      return Futures.immediateFuture(null);
    }

    // HTTP Store operations are asynchronous.
    return httpWriteExecutorService.submit(
        new Runnable() {
          @Override
          public void run() {
            Started startedEvent = HttpArtifactCacheEvent.newStoreStartedEvent(scheduled);
            buckEventBus.post(startedEvent);
            Finished.Builder finishedEventBuilder =
                HttpArtifactCacheEvent.newFinishedEventBuilder(startedEvent)
                    .setRuleKeys(ruleKeys);

            try {
              storeImpl(ruleKeys, metadata, tmp, finishedEventBuilder);
              buckEventBus.post(finishedEventBuilder.build());

            } catch (IOException e) {
              reportFailure(
                  e,
                  "store(%s): %s: %s",
                  ruleKeys,
                  e.getClass().getName(),
                  e.getMessage());

              buckEventBus.post(
                  finishedEventBuilder
                      .setWasUploadSuccessful(false)
                      .setErrorMessage(e.toString())
                      .build());
            }
            try {
              projectFilesystem.deleteFileAtPathIfExists(tmp);
            } catch (IOException e) {
              LOGGER.warn(e, "Failed to delete file %s", tmp);
            }
          }
        },
        /* result */ null
    );
  }

  /// depending on if we can borrow the output or not, we will either use output directly or
  /// hold it temporary in hidden place
  private Path getPathForArtifact(BorrowablePath output) throws IOException {
    Path tmp;
    if (output.canBorrow()) {
      tmp = output.getPath();
    } else {
      Optional<Path> tmpDirPath = projectFilesystem.getPathRelativeToProjectRoot(Paths.get("tmp"));
      projectFilesystem.mkdirs(tmpDirPath.get());
      tmp = projectFilesystem.createTempFile(tmpDirPath.get(), "artifact", ".tmp");
      projectFilesystem.copyFile(output.getPath(), tmp);
    }
    return tmp;
  }

  private void reportFailure(Exception exception, String format, Object... args) {
    LOGGER.warn(exception, format, args);
    reportFailureToEvenBus(format, args);
  }

  private void reportFailure(String format, Object... args) {
    LOGGER.warn(format, args);
    reportFailureToEvenBus(format, args);
  }

  private void reportFailureToEvenBus(String format, Object... args) {
    if (seenErrors.add(format)) {
      buckEventBus.post(ConsoleEvent.warning(
          errorTextTemplate
              .replaceAll("\\{cache_name}",
                  Matcher.quoteReplacement(name))
              .replaceAll("\\{error_message}",
                  Matcher.quoteReplacement(String.format(format, args)))));
    }
  }

  @Override
  public boolean isStoreSupported() {
    return doStore;
  }

  @Override
  public void close() {
    fetchClient.close();
    storeClient.close();
  }
}
