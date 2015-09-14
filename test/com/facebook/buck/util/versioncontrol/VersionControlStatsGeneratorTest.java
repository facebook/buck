/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.util.versioncontrol;

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.getCurrentArguments;
import static org.easymock.EasyMock.replay;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import com.facebook.buck.event.BuckEventBus;

import org.easymock.Capture;
import org.easymock.IAnswer;
import org.junit.Test;

import java.util.concurrent.ExecutorService;

public class VersionControlStatsGeneratorTest {

  private static final boolean HAS_WORKING_DIRECTORY_CHANGES = true;
  private static final String CURRENT_REVISION_ID = "current_revision_id";
  private static final String MASTER_REVISION_ID = "master_revision_id";
  private static final String BRANCHED_FROM_MASTER_REVISION_ID = "branched_from_master_revision_id";
  private static final String MASTER_BOOKMARK = "master";
  private static final long BRANCHED_FROM_MASTER_TS_SECS = 123L;
  private static final long BRANCHED_FROM_MASTER_TS_MILLIS = 123000L;
  private static final boolean IS_SUPPORTED_VCS = true;

  @Test
  public void testStartAsync() throws InterruptedException, VersionControlCommandFailedException {
    VersionControlCmdLineInterface cmdLineInterfaceMock =
        createMock(VersionControlCmdLineInterface.class);
    VersionControlCmdLineInterfaceFactory factoryMock =
        createMock(VersionControlCmdLineInterfaceFactory.class);
    BuckEventBus eventBus = createMock(BuckEventBus.class);

    expect(cmdLineInterfaceMock.isSupportedVersionControlSystem()).andReturn(
        IS_SUPPORTED_VCS);

    expect(cmdLineInterfaceMock.hasWorkingDirectoryChanges()).andReturn(
        HAS_WORKING_DIRECTORY_CHANGES);

    expect(cmdLineInterfaceMock.currentRevisionId()).andReturn(
        CURRENT_REVISION_ID);

    expect(cmdLineInterfaceMock.revisionId(MASTER_BOOKMARK)).andReturn(
        MASTER_REVISION_ID);

    expect(cmdLineInterfaceMock.commonAncestor(CURRENT_REVISION_ID, MASTER_REVISION_ID)).andReturn(
        BRANCHED_FROM_MASTER_REVISION_ID);

    expect(cmdLineInterfaceMock.timestampSeconds(BRANCHED_FROM_MASTER_REVISION_ID)).andReturn(
        BRANCHED_FROM_MASTER_TS_SECS);

    expect(factoryMock.createCmdLineInterface()).andReturn(cmdLineInterfaceMock);

    Capture<VersionControlStatsEvent> eventCapture = Capture.newInstance();
    eventBus.post(capture(eventCapture));
    expectLastCall().once();

    replay(eventBus, cmdLineInterfaceMock, factoryMock);
    ExecutorService executorService = sameThreadExecutorService();

    VersionControlStatsGenerator vcStatsGenerator = new VersionControlStatsGenerator(
        executorService,
        factoryMock,
        eventBus
    );

    vcStatsGenerator.generateStatsAsync();

    VersionControlStats vcStats = eventCapture.getValue().getVersionControlStats();
    assertThat(HAS_WORKING_DIRECTORY_CHANGES, is(equalTo(vcStats.workingDirectoryChanges())));
    assertThat(CURRENT_REVISION_ID, is(equalTo(vcStats.currentRevisionId())));
    assertThat(
        BRANCHED_FROM_MASTER_REVISION_ID,
        is(equalTo(vcStats.branchedFromMasterRevisionId())));
    assertThat(BRANCHED_FROM_MASTER_TS_MILLIS, is(equalTo(vcStats.branchedFromMasterTsMillis())));
  }

  private ExecutorService sameThreadExecutorService() {
    ExecutorService executorService = createMock(ExecutorService.class);
    executorService.submit(anyObject(Runnable.class));
    expectLastCall().andAnswer(
        new IAnswer<Object>() {
          @Override
          public Object answer() throws Throwable {
            Runnable runnable = (Runnable) getCurrentArguments()[0];
            runnable.run();
            return null;
          }
        }
    ).anyTimes();
    replay(executorService);
    return executorService;
  }
}
