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
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableSet;
import com.google.template.soy.data.SoyListData;
import com.google.template.soy.data.SoyMapData;

import org.eclipse.jetty.server.Request;

import java.io.IOException;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TracesHandlerDelegate extends AbstractTemplateHandlerDelegate {

  /**
   * Regex pattern that can be used as a parameter to {@link Pattern#compile(String)} to match a
   * valid trace id.
   */
  private static final String TRACE_ID_PATTERN_TEXT = "([0-9a-zA-Z-]+)";

  static final Pattern TRACE_ID_PATTERN = Pattern.compile(TRACE_ID_PATTERN_TEXT);

  private static final Pattern TRACE_FILE_NAME_PATTERN = Pattern.compile(
      "build\\.(?:[\\d\\-\\.]+\\.)?" + TRACE_ID_PATTERN + "\\.trace");

  private final TracesHelper tracesHelper;

  TracesHandlerDelegate(TracesHelper tracesHelper) {
    super(ImmutableSet.of("traces.soy"));
    this.tracesHelper = tracesHelper;
  }

  @Override
  public String getTemplateForRequest(Request baseRequest) {
    return "buck.trace";
  }

  @Override
  public SoyMapData getDataForRequest(Request baseRequest) throws IOException {
    return new SoyMapData("traces", getTraces());
  }

  @VisibleForTesting
  SoyListData getTraces() throws IOException {
    ImmutableCollection<Path> traceFiles = tracesHelper.listTraceFilesByLastModified();

    SoyListData traces = new SoyListData();
    for (Path path : traceFiles) {
      String name = path.getFileName().toString();
      Matcher matcher = TRACE_FILE_NAME_PATTERN.matcher(name);
      if (!matcher.matches()) {
        // Could be build.trace or launch.xxx.trace.
        continue;
      }

      SoyMapData trace = new SoyMapData();
      trace.put("name", name);
      trace.put("id", matcher.group(1));

      TraceAttributes traceAttributes = tracesHelper.getTraceAttributesFor(path);
      trace.put("dateTime", traceAttributes.getFormattedDateTime());
      if (traceAttributes.getCommand().isPresent()) {
        trace.put("command", traceAttributes.getCommand().get());
      } else {
        trace.put("command", "");
      }

      traces.add(trace);
    }
    return traces;
  }
}
