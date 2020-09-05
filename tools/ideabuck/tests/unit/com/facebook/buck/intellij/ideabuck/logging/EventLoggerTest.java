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

package com.facebook.buck.intellij.ideabuck.logging;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Test;

public class EventLoggerTest {

  @Test
  public void logSyncTest() {
    TestLoggerFactory testLoggerFactory = new TestLoggerFactory();
    EventLoggerFactoryProvider eventLoggerFactoryProvider =
        new EventLoggerFactoryProvider(
            () -> Arrays.asList(new EventLoggerFactory[] {testLoggerFactory}));

    BuckEventLogger buckEventLogger =
        eventLoggerFactoryProvider.getBuckEventLogger("", Runnable::run);

    String eventAction = "ea";
    Project project = EasyMock.createMock(Project.class);
    VirtualFile virtualFile = EasyMock.createMock(VirtualFile.class);
    Map<String, String> extraData = new HashMap<>();

    buckEventLogger
        .withEventAction(eventAction)
        .withProjectFiles(project, virtualFile)
        .withExtraData(extraData)
        .log();

    Assert.assertEquals(eventAction, TestLoggerFactory.testEventLogger.eventAction);
    Assert.assertEquals(project, TestLoggerFactory.testEventLogger.project);
    Assert.assertEquals(virtualFile, TestLoggerFactory.testEventLogger.virtualFiles.get(0));
    Assert.assertEquals(extraData, TestLoggerFactory.testEventLogger.extraData);
  }

  static class TestLoggerFactory implements EventLoggerFactory {

    static final TestLogger testEventLogger = new TestLogger();

    TestLoggerFactory() {}

    @Override
    public EventLogger eventLogger(String eventType) {
      return testEventLogger;
    }
  }

  static class TestLogger implements EventLogger {
    String eventAction;
    Project project;
    List<VirtualFile> virtualFiles;
    Map<String, String> extraData;

    TestLogger() {}

    @Override
    public EventLogger withEventAction(String eventAction) {
      this.eventAction = eventAction;
      return this;
    }

    @Override
    public EventLogger withProjectFiles(Project project, VirtualFile... virtualFiles) {
      this.project = project;
      this.virtualFiles = Arrays.asList(virtualFiles);
      return this;
    }

    @Override
    public EventLogger withExtraData(Map<String, String> extraData) {
      this.extraData = extraData;
      return this;
    }

    @Override
    public void log() {}
  }
}
