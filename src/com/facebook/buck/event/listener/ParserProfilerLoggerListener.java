/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.event.listener;

import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.InvocationInfo;
import com.facebook.buck.parser.events.ParseBuckProfilerReportEvent;
import com.google.common.eventbus.Subscribe;
import java.io.IOException;
import java.nio.file.Path;

public class ParserProfilerLoggerListener implements BuckEventListener {

  private final InvocationInfo info;
  private final ProjectFilesystem filesystem;

  public ParserProfilerLoggerListener(InvocationInfo info, ProjectFilesystem filesystem) {
    this.info = info;
    this.filesystem = filesystem;
  }

  @Subscribe
  public void parseProfilerReported(ParseBuckProfilerReportEvent event) throws IOException {
    Path tracePath =
        info.getLogDirectoryPath().resolve("parser-profiler" + event.getEventKey() + ".log");
    filesystem.createParentDirs(tracePath);
    filesystem.writeContentsToPath(event.getReport(), tracePath);
  }

  @Override
  public void outputTrace(BuildId buildId) {}
}
