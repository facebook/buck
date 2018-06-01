/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.httpserver;

import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.artifact_cache.ArtifactInfo;
import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.artifact_cache.HttpArtifactCacheBinaryProtocol;
import com.facebook.buck.artifact_cache.StoreResponseReadResult;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.io.file.BorrowablePath;
import com.facebook.buck.io.file.LazyPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteSource;
import com.google.common.util.concurrent.Futures;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Optional;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;

/** Implements a really simple cache server on top of the local dircache. */
public class ArtifactCacheHandler extends AbstractHandler {
  private static final Logger LOG = Logger.get(ArtifactCacheHandler.class);

  private final ProjectFilesystem projectFilesystem;
  private Optional<ArtifactCache> artifactCache;

  public ArtifactCacheHandler(ProjectFilesystem projectFilesystem) {
    this.artifactCache = Optional.empty();
    this.projectFilesystem = projectFilesystem;
  }

  public void setArtifactCache(Optional<ArtifactCache> artifactCache) {
    this.artifactCache = artifactCache;
  }

  @Override
  public void handle(
      String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    try {
      int status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
      String method = baseRequest.getMethod();
      if (method.equals("GET")) {
        status = handleGet(baseRequest, response);
      } else if (method.equals("PUT")) {
        status = handlePut(baseRequest, response);
      }
      response.setStatus(status);
    } catch (Exception e) {
      LOG.error(e, "Exception when handling request %s", target);
      e.printStackTrace(response.getWriter());
      response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    } finally {
      response.flushBuffer();
      baseRequest.setHandled(true);
    }
  }

  private int handleGet(Request baseRequest, HttpServletResponse response) throws IOException {
    if (!artifactCache.isPresent()) {
      response.getWriter().write("Serving local cache is disabled for this instance.");
      return HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
    }

    String path = baseRequest.getHttpURI().getPath();
    String[] pathElements = path.split("/");
    if (pathElements.length != 4 || !pathElements[2].equals("key")) {
      response.getWriter().write("Incorrect url format.");
      return HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
    }

    RuleKey ruleKey = new RuleKey(pathElements[3]);

    Path temp = null;
    try {
      projectFilesystem.mkdirs(projectFilesystem.getBuckPaths().getScratchDir());
      temp =
          projectFilesystem.createTempFile(
              projectFilesystem.getBuckPaths().getScratchDir(), "outgoing_rulekey", ".tmp");
      CacheResult fetchResult =
          Futures.getUnchecked(
              artifactCache.get().fetchAsync(null, ruleKey, LazyPath.ofInstance(temp)));
      if (!fetchResult.getType().isSuccess()) {
        return HttpServletResponse.SC_NOT_FOUND;
      }

      Path tempFinal = temp;
      HttpArtifactCacheBinaryProtocol.FetchResponse fetchResponse =
          new HttpArtifactCacheBinaryProtocol.FetchResponse(
              ImmutableSet.of(ruleKey),
              fetchResult.getMetadata(),
              new ByteSource() {
                @Override
                public InputStream openStream() throws IOException {
                  return projectFilesystem.newFileInputStream(tempFinal);
                }
              });
      fetchResponse.write(response.getOutputStream());
      response.setContentLengthLong(fetchResponse.getContentLength());
      return HttpServletResponse.SC_OK;
    } finally {
      if (temp != null) {
        projectFilesystem.deleteFileAtPathIfExists(temp);
      }
    }
  }

  private int handlePut(Request baseRequest, HttpServletResponse response) throws IOException {
    if (!artifactCache.isPresent()) {
      response.getWriter().write("Serving local cache is disabled for this instance.");
      return HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
    }

    Path temp = null;
    try {
      projectFilesystem.mkdirs(projectFilesystem.getBuckPaths().getScratchDir());
      temp =
          projectFilesystem.createTempFile(
              projectFilesystem.getBuckPaths().getScratchDir(), "incoming_upload", ".tmp");

      StoreResponseReadResult storeRequest;
      try (DataInputStream requestInputData = new DataInputStream(baseRequest.getInputStream());
          OutputStream tempFileOutputStream = projectFilesystem.newFileOutputStream(temp)) {
        storeRequest =
            HttpArtifactCacheBinaryProtocol.readStoreRequest(
                requestInputData, tempFileOutputStream);
      }

      if (!storeRequest.getActualHashCode().equals(storeRequest.getExpectedHashCode())) {
        response.getWriter().write("Checksum mismatch.");
        return HttpServletResponse.SC_NOT_ACCEPTABLE;
      }

      artifactCache
          .get()
          .store(
              ArtifactInfo.builder()
                  .setRuleKeys(storeRequest.getRuleKeys())
                  .setMetadata(storeRequest.getMetadata())
                  .build(),
              BorrowablePath.borrowablePath(temp));
      return HttpServletResponse.SC_ACCEPTED;
    } finally {
      if (temp != null) {
        projectFilesystem.deleteFileAtPathIfExists(temp);
      }
    }
  }
}
