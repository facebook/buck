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

import com.facebook.buck.httpserver.TracesHelper.TraceAttributes;
import com.google.common.collect.ImmutableSet;
import com.google.template.soy.data.SoyMapData;

import org.eclipse.jetty.server.Request;

import java.io.IOException;
import java.util.regex.Matcher;

import javax.annotation.Nullable;

/**
 * HTTP handler for requests to the {@code /trace} path.
 */
public class TraceHandlerDelegate extends AbstractTemplateHandlerDelegate {

  private final TracesHelper tracesHelper;

  TraceHandlerDelegate(TracesHelper tracesHelper) {
    super(ImmutableSet.of("trace.soy"));
    this.tracesHelper = tracesHelper;
  }

  @Override
  public String getTemplateForRequest(Request baseRequest) {
    return "buck.trace";
  }

  @Override
  @Nullable
  public SoyMapData getDataForRequest(Request baseRequest) throws IOException {
    String path = baseRequest.getPathInfo();
    Matcher matcher = TraceDataHandler.ID_PATTERN.matcher(path);
    if (!matcher.matches()) {
      return null;
    }

    SoyMapData templateData = new SoyMapData();

    String id = matcher.group(1);
    templateData.put("traceId", id);

    // Read the args to `buck` out of the Chrome Trace.
    TraceAttributes traceAttributes = tracesHelper.getTraceAttributesFor(id);
    templateData.put("dateTime", traceAttributes.getFormattedDateTime());
    if (traceAttributes.getCommand().isPresent()) {
      templateData.put("command", traceAttributes.getCommand().get());
    }

    return templateData;
  }
}
