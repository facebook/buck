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
import static com.facebook.buck.util.versioncontrol.VersionControlStatsGenerator.TRACKED_BOOKMARKS;

import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.google.common.collect.ImmutableSet;

import org.easymock.Capture;
import org.easymock.IAnswer;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.ExecutorService;

public class VersionControlStatsGeneratorTest {

  private static final ImmutableSet<String> WORKING_DIRECTORY_CHANGES = ImmutableSet.of("dog");
  private static final String CURRENT_REVISION_ID = "current_revision_id";
  private static final String MASTER_REVISION_ID = "master_revision_id";
  private static final String BRANCHED_FROM_MASTER_REVISION_ID = "branched_from_master_revision_id";
  private static final String MASTER_BOOKMARK = "master";
  private static final long BRANCHED_FROM_MASTER_TS_SECS = 123L;
  private static final long BRANCHED_FROM_MASTER_TS_MILLIS = 123000L;
  private static final boolean SUPPORTED_VCS = true;

  @Test
  public void givenSupportedVcsWhenGenerateStatsAsyncThenPostEvent()
      throws InterruptedException, VersionControlCommandFailedException {
    VersionControlCmdLineInterface cmdLineInterfaceMock =
        createMock(VersionControlCmdLineInterface.class);
    VersionControlCmdLineInterfaceFactory factoryMock =
        createMock(VersionControlCmdLineInterfaceFactory.class);
    BuckEventBus eventBus = createMock(BuckEventBus.class);

    expect(cmdLineInterfaceMock.isSupportedVersionControlSystem()).andReturn(
        SUPPORTED_VCS);

    expect(cmdLineInterfaceMock.changedFiles(".")).andReturn(WORKING_DIRECTORY_CHANGES);

    expect(cmdLineInterfaceMock.currentRevisionId()).andReturn(
        CURRENT_REVISION_ID);

    expect(cmdLineInterfaceMock.revisionId(MASTER_BOOKMARK)).andReturn(
        MASTER_REVISION_ID);

    expect(cmdLineInterfaceMock.commonAncestor(CURRENT_REVISION_ID, MASTER_REVISION_ID)).andReturn(
        BRANCHED_FROM_MASTER_REVISION_ID);

    expect(cmdLineInterfaceMock.timestampSeconds(BRANCHED_FROM_MASTER_REVISION_ID)).andReturn(
        BRANCHED_FROM_MASTER_TS_SECS);

    expect(cmdLineInterfaceMock.trackedBookmarksOffRevisionId(
        MASTER_REVISION_ID,
        CURRENT_REVISION_ID,
        TRACKED_BOOKMARKS))
        .andReturn(ImmutableSet.of());

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

    AbstractVersionControlStats vcStats = eventCapture.getValue().getVersionControlStats();
    assertThat(
        WORKING_DIRECTORY_CHANGES.size(),
        is(equalTo(vcStats.getPathsChangedInWorkingDirectory().size())));
    assertThat(CURRENT_REVISION_ID, is(equalTo(vcStats.getCurrentRevisionId())));
    assertThat(
        BRANCHED_FROM_MASTER_REVISION_ID,
        is(equalTo(vcStats.getBranchedFromMasterRevisionId())));
    assertThat(
        BRANCHED_FROM_MASTER_TS_MILLIS,
        is(equalTo(vcStats.getBranchedFromMasterTsMillis())));
  }

  @Test
  public void givenNoOpWhenGenerateStatsAsyncThenPostNothing()
      throws InterruptedException, VersionControlCommandFailedException {
    VersionControlCmdLineInterface cmdLineInterfaceMock = new NoOpCmdLineInterface();
    VersionControlCmdLineInterfaceFactory factoryMock =
        createMock(VersionControlCmdLineInterfaceFactory.class);
    BuckEventBus eventBus = createMock(BuckEventBus.class);

    expect(factoryMock.createCmdLineInterface()).andReturn(cmdLineInterfaceMock);

    // Nothing should be posted to the event bus
    eventBus.post(anyObject(BuckEvent.class));
    expectLastCall().andAnswer(
        new IAnswer<BuckEvent>() {
          @Override
          public BuckEvent answer() {
            Assert.fail("Event posted to event bus, but was expecting no events.");
            return null;
          }
        }).anyTimes();

    replay(eventBus, factoryMock);
    ExecutorService executorService = sameThreadExecutorService();

    VersionControlStatsGenerator vcStatsGenerator = new VersionControlStatsGenerator(
        executorService,
        factoryMock,
        eventBus
    );

    vcStatsGenerator.generateStatsAsync();
  }

  private ExecutorService sameThreadExecutorService() {
    ExecutorService executorService = createMock(ExecutorService.class);
    executorService.submit(anyObject(Runnable.class));
    expectLastCall().andAnswer(
        () -> {
          Runnable runnable = (Runnable) getCurrentArguments()[0];
          runnable.run();
          return null;
        }
    ).anyTimes();
    replay(executorService);
    return executorService;
  }
}
