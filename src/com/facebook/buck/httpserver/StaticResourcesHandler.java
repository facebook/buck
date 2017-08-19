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

package com.facebook.buck.httpserver;

import com.facebook.buck.log.Logger;
import com.google.common.base.Charsets;
import com.google.common.io.ByteStreams;
import com.google.common.net.MediaType;
import java.io.IOException;
import java.io.InputStream;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;

/**
 * Handler to serve static resources out of the resources/static directory.
 *
 * <p>TODO(mbolin): This implementation is lame: it does not cache anything in memory, it does not
 * send the appropriate headers to do proper HTTP caching, it doesn't stream responses, etc. We can
 * revisit performance issues once people use this feature enough to make it worth optimizing.
 */
class StaticResourcesHandler extends AbstractHandler {
  private static final Logger LOG = Logger.get(StaticResourcesHandler.class);

  @Override
  public void handle(
      String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
      throws IOException, ServletException {
    if (!"GET".equals(request.getMethod())) {
      Responses.writeFailedResponse(baseRequest, response);
      return;
    }

    String path = baseRequest.getUri().getPath();
    if ("/static/test_websocket.html".equals(path)) {
      String html = getContentsForResource(path);
      Responses.writeSuccessfulResponse(html, MediaType.HTML_UTF_8, baseRequest, response);
    } else if ("/static/test_websocket.js".equals(path)) {
      String js = getContentsForResource(path);
      Responses.writeSuccessfulResponse(js, MediaType.JAVASCRIPT_UTF_8, baseRequest, response);
    } else if ("/static/theme.css".equals(path)) {
      String css = getContentsForResource(path);
      Responses.writeSuccessfulResponse(css, MediaType.CSS_UTF_8, baseRequest, response);
    } else if ("/static/trace_viewer.css".equals(path)) {
      String css = getContentsForResource(path);
      Responses.writeSuccessfulResponse(css, MediaType.CSS_UTF_8, baseRequest, response);
    } else if ("/static/trace_viewer.js".equals(path)) {
      String js = getContentsForResource(path);
      Responses.writeSuccessfulResponse(js, MediaType.JAVASCRIPT_UTF_8, baseRequest, response);
    } else {
      LOG.error("No handler for %s", path);
      Responses.writeFailedResponse(baseRequest, response);
    }
  }

  /**
   * Note that this is a private method and we always pass a known value for {@code path} into this
   * method so that it is not possible to read an arbitrary resource via this method.
   *
   * @param path is a relative path under the resources folder for this package.
   */
  private String getContentsForResource(String path) throws IOException {
    InputStream input = getClass().getResourceAsStream(String.format("resources%s", path));
    byte[] bytes = ByteStreams.toByteArray(input);
    return new String(bytes, Charsets.UTF_8);
  }
}
