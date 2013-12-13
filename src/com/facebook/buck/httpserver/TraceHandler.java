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

import com.google.common.net.MediaType;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;

import java.io.IOException;
import java.util.regex.Matcher;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * HTTP handler for requests to the {@code /tracedata} path.
 */
public class TraceHandler extends AbstractHandler {

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

  @SuppressWarnings("PMD.AddEmptyString")
  private void doGet(Request baseRequest, HttpServletResponse response)
      throws ServletException, IOException {
    String path = baseRequest.getPathInfo();

    Matcher matcher = TraceDataHandler.ID_PATTERN.matcher(path);
    if (matcher.matches()) {
      String id = matcher.group(1);
      String html = "<!doctype html>" +
      		"<html>" +
      		"<head>" +
      		"  <link type='text/css' rel='stylesheet' href='/static/trace_viewer.css'>" +
      		"</head>" +
      		"<body>" +
      		"  <script src='/static/trace_viewer.js'></script>" +
      		"  <script>" +
      		"  var onTraceLoaded = function(trace) {" +
      		"    var model = new tracing.TraceModel();" +
      		"    model.importTraces([trace]);" +
      		"" +
      		"    var viewEl = tracing.TimelineView();" +
      		"    viewEl.model = model;" +
      		"    document.body.appendChild(viewEl);" +
      		"  };" +
      		"  </script>" +
      		"  <script src='/tracedata/" + id + "?callback=onTraceLoaded'></script>" +
      		"</body>" +
      		"</html>";
      Responses.writeSuccessfulResponse(html, MediaType.HTML_UTF_8, baseRequest, response);
    } else {
      Responses.writeFailedResponse(baseRequest, response);
    }
  }
}
