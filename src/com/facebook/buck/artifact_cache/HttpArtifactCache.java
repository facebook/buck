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

import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.file.LazyPath;
import com.facebook.buck.slb.HttpResponse;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteSource;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
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
  protected FetchResult fetchImpl(RuleKey ruleKey, LazyPath output) throws IOException {
    FetchResult.Builder resultBuilder = FetchResult.builder();
    Request.Builder requestBuilder = new Request.Builder().get();
    try (HttpResponse response =
        fetchClient.makeRequest("/artifacts/key/" + ruleKey, requestBuilder)) {
      resultBuilder.setResponseSizeBytes(response.contentLength());

      try (DataInputStream input =
          new DataInputStream(new FullyReadOnCloseInputStream(response.getBody()))) {

        if (response.statusCode() == HttpURLConnection.HTTP_NOT_FOUND) {
          LOG.info("fetch(%s, %s): cache miss", response.requestUrl(), ruleKey);
          return resultBuilder.setCacheResult(CacheResult.miss()).build();
        }

        if (response.statusCode() != HttpURLConnection.HTTP_OK) {
          String msg =
              String.format(
                  "unexpected server response: [%d:%s]",
                  response.statusCode(), response.statusMessage());
          reportFailureWithFormatKey("fetch(%s, %s): %s", response.requestUrl(), ruleKey, msg);
          return resultBuilder.setCacheResult(CacheResult.error(getName(), getMode(), msg)).build();
        }

        // Setup a temporary file, which sits next to the destination, to write to and
        // make sure all parent dirs exist.
        Path file = output.get();
        getProjectFilesystem().createParentDirs(file);
        Path temp =
            getProjectFilesystem()
                .createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");

        FetchResponseReadResult fetchedData;
        try (OutputStream tempFileOutputStream = getProjectFilesystem().newFileOutputStream(temp)) {
          fetchedData =
              HttpArtifactCacheBinaryProtocol.readFetchResponse(input, tempFileOutputStream);
        }

        resultBuilder
            .setBuildTarget(ArtifactCacheEvent.getTarget(fetchedData.getMetadata()))
            .setResponseSizeBytes(fetchedData.getResponseSizeBytes())
            .setArtifactContentHash(fetchedData.getArtifactOnlyHashCode().toString());

        // Verify that we were one of the rule keys that stored this artifact.
        if (!fetchedData.getRuleKeys().contains(ruleKey)) {
          String msg = "incorrect key name";
          reportFailureWithFormatKey("fetch(%s, %s): %s", response.requestUrl(), ruleKey, msg);
          return resultBuilder.setCacheResult(CacheResult.error(getName(), getMode(), msg)).build();
        }

        // Now form the checksum on the file we got and compare it to the checksum form the
        // the HTTP header.  If it's incorrect, log this and return a miss.
        if (!fetchedData.getExpectedHashCode().equals(fetchedData.getActualHashCode())) {
          String msg = "artifact had invalid checksum";
          reportFailureWithFormatKey("fetch(%s, %s): %s", response.requestUrl(), ruleKey, msg);
          getProjectFilesystem().deleteFileAtPath(temp);
          return resultBuilder.setCacheResult(CacheResult.error(getName(), getMode(), msg)).build();
        }

        // Finally, move the temp file into it's final place.
        getProjectFilesystem().move(temp, file, StandardCopyOption.REPLACE_EXISTING);

        LOG.info("fetch(%s, %s): cache hit", response.requestUrl(), ruleKey);
        return resultBuilder
            .setCacheResult(
                CacheResult.hit(
                    getName(),
                    getMode(),
                    fetchedData.getMetadata(),
                    fetchedData.getResponseSizeBytes()))
            .build();
      }
    }
  }

  @Override
  protected MultiContainsResult multiContainsImpl(ImmutableSet<RuleKey> ruleKeys) {
    throw new UnsupportedOperationException("multiContains is not supported");
  }

  @Override
  protected StoreResult storeImpl(ArtifactInfo info, Path file) throws IOException {
    StoreResult.Builder resultBuilder = StoreResult.builder();

    // Build the request, hitting the multi-key endpoint.
    Request.Builder builder = new Request.Builder();
    HttpArtifactCacheBinaryProtocol.StoreRequest storeRequest =
        new HttpArtifactCacheBinaryProtocol.StoreRequest(
            info,
            new ByteSource() {
              @Override
              public InputStream openStream() throws IOException {
                return getProjectFilesystem().newFileInputStream(file);
              }
            });

    resultBuilder.setRequestSizeBytes(storeRequest.getContentLength());

    // Wrap the file into a `RequestBody` which uses `ProjectFilesystem`.
    builder.put(
        new RequestBody() {
          @Override
          public MediaType contentType() {
            return OCTET_STREAM_CONTENT_TYPE;
          }

          @Override
          public long contentLength() {
            return storeRequest.getContentLength();
          }

          @Override
          public void writeTo(BufferedSink bufferedSink) throws IOException {
            StoreWriteResult writeResult = storeRequest.write(bufferedSink.outputStream());
            resultBuilder.setArtifactContentHash(
                writeResult.getArtifactContentHashCode().toString());
          }
        });

    // Dispatch the store operation and verify it succeeded.
    try (HttpResponse response = storeClient.makeRequest("/artifacts/key", builder)) {
      boolean requestFailed = response.statusCode() != HttpURLConnection.HTTP_ACCEPTED;
      if (requestFailed) {
        reportFailureWithFormatKey(
            "store(%s, %s): unexpected response: [%d:%s].",
            response.requestUrl(),
            info.getRuleKeys(),
            response.statusCode(),
            response.statusMessage());
      }

      resultBuilder.setWasStoreSuccessful(!requestFailed);
    }
    return resultBuilder.build();
  }

  @Override
  protected CacheDeleteResult deleteImpl(List<RuleKey> ruleKeys) {
    throw new RuntimeException("Delete operation is not yet supported");
  }

  @Override
  protected MultiFetchResult multiFetchImpl(
      Iterable<AbstractAsynchronousCache.FetchRequest> requests) {
    throw new RuntimeException("multiFetch not supported");
  }
}
