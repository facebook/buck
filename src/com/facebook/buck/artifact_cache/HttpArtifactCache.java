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
import com.facebook.buck.io.LazyPath;
import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.slb.HttpResponse;
import com.google.common.io.ByteSource;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okio.BufferedSink;

public final class HttpArtifactCache extends AbstractNetworkCache {

  public static final MediaType OCTET_STREAM_CONTENT_TYPE =
      MediaType.parse("application/octet-stream");

  /**
   * If the user is offline, then we do not want to print every connection failure that occurs.
   * However, in practice, it appears that some connection failures can be intermittent, so we
   * should print enough to provide a signal of how flaky the connection is.
   */
  private static final Logger LOG = Logger.get(HttpArtifactCache.class);

  public HttpArtifactCache(NetworkCacheArgs args) {
    super(args);
  }

  @Override
  protected CacheResult fetchImpl(
      RuleKey ruleKey, LazyPath output, final Finished.Builder eventBuilder) throws IOException {

    Request.Builder requestBuilder = new Request.Builder().get();
    try (HttpResponse response =
        fetchClient.makeRequest("/artifacts/key/" + ruleKey.toString(), requestBuilder)) {
      eventBuilder.getFetchBuilder().setResponseSizeBytes(response.contentLength());

      try (DataInputStream input =
          new DataInputStream(new FullyReadOnCloseInputStream(response.getBody()))) {

        if (response.statusCode() == HttpURLConnection.HTTP_NOT_FOUND) {
          LOG.info("fetch(%s, %s): cache miss", response.requestUrl(), ruleKey);
          return CacheResult.miss();
        }

        if (response.statusCode() != HttpURLConnection.HTTP_OK) {
          String msg =
              String.format(
                  "unexpected server response: [%d:%s]",
                  response.statusCode(), response.statusMessage());
          reportFailure("fetch(%s, %s): %s", response.requestUrl(), ruleKey, msg);
          eventBuilder.getFetchBuilder().setErrorMessage(msg);
          return CacheResult.error(name, mode, msg);
        }

        // Setup a temporary file, which sits next to the destination, to write to and
        // make sure all parent dirs exist.
        Path file = output.get();
        projectFilesystem.createParentDirs(file);
        Path temp =
            projectFilesystem.createTempFile(
                file.getParent(), file.getFileName().toString(), ".tmp");

        FetchResponseReadResult fetchedData;
        try (OutputStream tempFileOutputStream = projectFilesystem.newFileOutputStream(temp)) {
          fetchedData =
              HttpArtifactCacheBinaryProtocol.readFetchResponse(input, tempFileOutputStream);
        }

        eventBuilder
            .setTarget(ArtifactCacheEvent.getTarget(fetchedData.getMetadata()))
            .getFetchBuilder()
            .setResponseSizeBytes(fetchedData.getResponseSizeBytes())
            .setArtifactContentHash(fetchedData.getArtifactOnlyHashCode().toString());

        // Verify that we were one of the rule keys that stored this artifact.
        if (!fetchedData.getRuleKeys().contains(ruleKey)) {
          String msg = "incorrect key name";
          reportFailure("fetch(%s, %s): %s", response.requestUrl(), ruleKey, msg);
          eventBuilder.getFetchBuilder().setErrorMessage(msg);
          return CacheResult.error(name, mode, msg);
        }

        // Now form the checksum on the file we got and compare it to the checksum form the
        // the HTTP header.  If it's incorrect, log this and return a miss.
        if (!fetchedData.getExpectedHashCode().equals(fetchedData.getActualHashCode())) {
          String msg = "artifact had invalid checksum";
          reportFailure("fetch(%s, %s): %s", response.requestUrl(), ruleKey, msg);
          projectFilesystem.deleteFileAtPath(temp);
          eventBuilder.getFetchBuilder().setErrorMessage(msg);
          return CacheResult.error(name, mode, msg);
        }

        // Finally, move the temp file into it's final place.
        projectFilesystem.move(temp, file, StandardCopyOption.REPLACE_EXISTING);

        LOG.info("fetch(%s, %s): cache hit", response.requestUrl(), ruleKey);
        return CacheResult.hit(
            name, mode, fetchedData.getMetadata(), fetchedData.getResponseSizeBytes());
      }
    }
  }

  @Override
  protected void storeImpl(ArtifactInfo info, final Path file, final Finished.Builder eventBuilder)
      throws IOException {

    // Build the request, hitting the multi-key endpoint.
    Request.Builder builder = new Request.Builder();
    final HttpArtifactCacheBinaryProtocol.StoreRequest storeRequest =
        new HttpArtifactCacheBinaryProtocol.StoreRequest(
            info,
            new ByteSource() {
              @Override
              public InputStream openStream() throws IOException {
                return projectFilesystem.newFileInputStream(file);
              }
            });

    eventBuilder.getStoreBuilder().setRequestSizeBytes(storeRequest.getContentLength());

    // Wrap the file into a `RequestBody` which uses `ProjectFilesystem`.
    builder.put(
        new RequestBody() {
          @Override
          public MediaType contentType() {
            return OCTET_STREAM_CONTENT_TYPE;
          }

          @Override
          public long contentLength() throws IOException {
            return storeRequest.getContentLength();
          }

          @Override
          public void writeTo(BufferedSink bufferedSink) throws IOException {
            StoreWriteResult writeResult = storeRequest.write(bufferedSink.outputStream());
            eventBuilder
                .getStoreBuilder()
                .setArtifactContentHash(writeResult.getArtifactContentHashCode().toString());
          }
        });

    // Dispatch the store operation and verify it succeeded.
    try (HttpResponse response = storeClient.makeRequest("/artifacts/key", builder)) {
      final boolean requestFailed = response.statusCode() != HttpURLConnection.HTTP_ACCEPTED;
      if (requestFailed) {
        reportFailure(
            "store(%s, %s): unexpected response: [%d:%s].",
            response.requestUrl(),
            info.getRuleKeys(),
            response.statusCode(),
            response.statusMessage());
      }

      eventBuilder.getStoreBuilder().setWasStoreSuccessful(!requestFailed);
    }
  }
}
