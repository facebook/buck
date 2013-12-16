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

import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.template.soy.data.SoyListData;
import com.google.template.soy.data.SoyMapData;

import org.eclipse.jetty.server.Request;

import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TracesHandlerDelegate extends AbstractTemplateHandlerDelegate {

  /**
   * The file that was modified most recently will be listed first. Ties are broken by
   * lexicographical sorting by normalized file path.
   */
  private static final Comparator<File> SORT_BY_LAST_MODIFIED = new Comparator<File>() {
    @Override
    public int compare(File file1, File file2) {
      long lastModifiedTimeDifference = file2.lastModified() - file1.lastModified();
      if (lastModifiedTimeDifference != 0) {
        return (int) lastModifiedTimeDifference;
      }

      return file1.toPath().normalize().compareTo(file2.toPath().normalize());
    }
  };

  private static final Pattern TRACE_FILE_NAME_PATTERN = Pattern.compile(
      "build\\.([0-9a-zA-Z]+)\\.trace");
  private static final String TRACE_TO_IGNORE = "build.trace";
  private static final ThreadLocal<DateFormat> DATE_FORMAT = new ThreadLocal<DateFormat>() {
    @Override
    protected DateFormat initialValue() {
      return new SimpleDateFormat("EEE, MMM d h:mm a");
    }
  };

  private final ProjectFilesystem projectFilesystem;

  TracesHandlerDelegate(ProjectFilesystem projectFilesystem) {
    super(ImmutableSet.of("traces.soy"));
    this.projectFilesystem = Preconditions.checkNotNull(projectFilesystem);
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
    File[] traceFiles = projectFilesystem.listFiles(BuckConstant.BUCK_TRACE_DIR);
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

      // TODO(mbolin): Read the args to `buck` out of the Chrome Trace. Then include them
      // in the SoyMapData for the trace.

      long lastModifiedTime = file.lastModified();
      String dateTime;
      if (lastModifiedTime != 0) {
        dateTime = DATE_FORMAT.get().format(new Date(lastModifiedTime));
      } else {
        dateTime = "";
      }
      trace.put("dateTime", dateTime);

      traces.add(trace);
    }
    return traces;
  }
}
