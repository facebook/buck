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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.io.CharStreams;
import com.google.common.net.MediaType;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Writer;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * HTTP handler for requests to the {@code /tracedata} path.
 */
class TraceDataHandler extends AbstractHandler {

  static final Pattern ID_PATTERN = Pattern.compile("/([0-9a-zA-Z-]+)");

  @VisibleForTesting
  static final Pattern CALLBACK_PATTERN = Pattern.compile("[\\w\\.]+");

  private final TracesHelper tracesHelper;

  TraceDataHandler(TracesHelper tracesHelper) {
    this.tracesHelper = tracesHelper;
  }

  @Override
  public void handle(String target,
      Request baseRequest,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException, ServletException {
    if ("GET".equals(baseRequest.getMethod())) {
      doGet(baseRequest, response);
    } else {
      Responses.writeFailedResponse(baseRequest, response);
    }
  }

  private void doGet(Request baseRequest, HttpServletResponse response)
      throws ServletException, IOException {
    String path = baseRequest.getPathInfo();
    Matcher matcher = ID_PATTERN.matcher(path);

    if (!matcher.matches()) {
      Responses.writeFailedResponse(baseRequest, response);
      return;
    }

    String id = matcher.group(1);

    response.setContentType(MediaType.JAVASCRIPT_UTF_8.toString());
    response.setStatus(HttpServletResponse.SC_OK);

    boolean hasValidCallbackParam = false;
    Writer responseWriter = response.getWriter();
    String callback = baseRequest.getParameter("callback");
    if (callback != null) {
      Matcher callbackMatcher = CALLBACK_PATTERN.matcher(callback);
      if (callbackMatcher.matches()) {
        hasValidCallbackParam = true;
        responseWriter.write(callback);
        responseWriter.write("(");
      }
    }

    responseWriter.write("[");

    Iterator<InputStream> traceStreams = tracesHelper.getInputsForTraces(id).iterator();
    boolean isFirst = true;

    while (traceStreams.hasNext()) {
      if (!isFirst) {
        responseWriter.write(",");
      } else {
        isFirst = false;
      }
      try (
          InputStream input = traceStreams.next();
          InputStreamReader inputStreamReader = new InputStreamReader(input)) {
        CharStreams.copy(inputStreamReader, responseWriter);
      }
    }

    responseWriter.write("]");

    if (hasValidCallbackParam) {
      responseWriter.write(");\n");
    }

    response.flushBuffer();
    baseRequest.setHandled(true);
  }
}
