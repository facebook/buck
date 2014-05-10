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
import com.google.common.collect.ImmutableSet;
import com.google.template.soy.data.SoyListData;
import com.google.template.soy.data.SoyMapData;

import org.eclipse.jetty.server.Request;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TracesHandlerDelegate extends AbstractTemplateHandlerDelegate {

  /**
   * The file that was modified most recently will be listed first. Ties are broken by
   * lexicographical sorting by normalized file path.
   */
  @VisibleForTesting
  static final Comparator<File> SORT_BY_LAST_MODIFIED = new Comparator<File>() {
    @Override
    public int compare(File file1, File file2) {
      long lastModifiedTimeDifference = file2.lastModified() - file1.lastModified();
      if (lastModifiedTimeDifference != 0) {
        return Long.signum(lastModifiedTimeDifference);
      }

      return file1.toPath().normalize().compareTo(file2.toPath().normalize());
    }
  };

  private static final Pattern TRACE_FILE_NAME_PATTERN = Pattern.compile(
      "build\\.([0-9a-zA-Z-]+)\\.trace");
  private static final String TRACE_TO_IGNORE = "build.trace";

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
  SoyListData getTraces() {
    File[] traceFiles = tracesHelper.listTraceFiles();
    Arrays.sort(traceFiles, SORT_BY_LAST_MODIFIED);

    SoyListData traces = new SoyListData();
    for (File file : traceFiles) {
      String name = file.getName();
      if (TRACE_TO_IGNORE.equals(name)) {
        continue;
      }

      SoyMapData trace = new SoyMapData();
      trace.put("name", name);

      Matcher matcher = TRACE_FILE_NAME_PATTERN.matcher(name);
      if (matcher.matches()) {
        trace.put("id", matcher.group(1));
      }

      TraceAttributes traceAttributes = tracesHelper.getTraceAttributesFor(file);
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
