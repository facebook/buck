/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.httpserver;

import com.facebook.buck.util.trace.BuildTraces;
import com.facebook.buck.util.trace.BuildTraces.TraceAttributes;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Resources;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import org.eclipse.jetty.server.Request;

/** Template and parameters for the /traces page. */
public class TracesHandlerDelegate implements TemplateHandlerDelegate {

  private static final Pattern TRACE_FILE_NAME_PATTERN =
      Pattern.compile("build\\.(?:[\\d\\-\\.]+\\.)?" + BuildTraces.TRACE_ID_PATTERN + "\\.trace");

  private final BuildTraces buildTraces;

  TracesHandlerDelegate(BuildTraces buildTraces) {
    this.buildTraces = buildTraces;
  }

  @Override
  public URL getTemplateGroup() {
    return Resources.getResource(TracesHandlerDelegate.class, "templates.stg");
  }

  @Override
  public String getTemplateForRequest(Request baseRequest) {
    return "traces";
  }

  @Override
  public ImmutableMap<String, Object> getDataForRequest(Request baseRequest) throws IOException {
    return ImmutableMap.of("traces", getTraces());
  }

  @VisibleForTesting
  List<TraceAttrs> getTraces() throws IOException {
    List<Path> traceFiles = buildTraces.listTraceFilesByLastModified();

    List<TraceAttrs> traces = new ArrayList<>();
    for (Path path : traceFiles) {
      String name = path.getFileName().toString();
      Matcher matcher = TRACE_FILE_NAME_PATTERN.matcher(name);
      if (!matcher.matches()) {
        // Could be build.trace or launch.xxx.trace.
        continue;
      }

      TraceAttributes traceAttributes = buildTraces.getTraceAttributesFor(path);
      traces.add(
          new TraceAttrs(
              name,
              matcher.group(1),
              traceAttributes.getFormattedDateTime(),
              traceAttributes.getCommand().orElse(null)));
    }
    return traces;
  }

  /** Attributes for traces. */
  public static class TraceAttrs {
    public final String name;
    public final String id;
    public final String dateTime;
    @Nullable public final String command;

    public TraceAttrs(String name, String id, String dateTime, @Nullable String command) {
      this.name = name;
      this.id = id;
      this.dateTime = dateTime;
      this.command = command;
    }
  }
}
