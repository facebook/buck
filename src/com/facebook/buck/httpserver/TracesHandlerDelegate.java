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

import com.facebook.buck.util.trace.BuildTraces;
import com.facebook.buck.util.trace.BuildTraces.TraceAttributes;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.template.soy.data.SoyListData;
import com.google.template.soy.data.SoyMapData;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.jetty.server.Request;

public class TracesHandlerDelegate extends AbstractTemplateHandlerDelegate {

  private static final Pattern TRACE_FILE_NAME_PATTERN =
      Pattern.compile("build\\.(?:[\\d\\-\\.]+\\.)?" + BuildTraces.TRACE_ID_PATTERN + "\\.trace");

  private final BuildTraces buildTraces;

  TracesHandlerDelegate(BuildTraces buildTraces) {
    super(ImmutableSet.of("traces.soy"));
    this.buildTraces = buildTraces;
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
    List<Path> traceFiles = buildTraces.listTraceFilesByLastModified();
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

      TraceAttributes traceAttributes = buildTraces.getTraceAttributesFor(path);
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
