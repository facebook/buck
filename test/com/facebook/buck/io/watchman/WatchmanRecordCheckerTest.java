/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.io.watchman;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.event.WatchmanStatusEvent;
import com.facebook.buck.util.timing.FakeClock;
import com.facebook.buck.util.versioncontrol.HgCmdLineInterface;
import com.facebook.buck.util.versioncontrol.VersionControlCommandFailedException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.eventbus.Subscribe;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class WatchmanRecordCheckerTest {
  private static final AbsPath FAKE_ROOT = AbsPath.of(Paths.get("/fake/root").toAbsolutePath());
  private static final WatchmanWatcherQuery FAKE_QUERY =
      ImmutableWatchmanWatcherQuery.ofImpl(
          new WatchRoot("/fake/root", true), "fake-expr", ImmutableList.of(), ForwardRelPath.EMPTY);
  private static final WatchmanQuery.Query FAKE_UUID_QUERY = FAKE_QUERY.toQuery("n:buckduuid");
  private static final WatchmanQuery.Query FAKE_CLOCK_QUERY = FAKE_QUERY.toQuery("c:0:0");
  private static final String BASE_COMMIT = "aaaaaaa";
  private static final AbsPath FAKE_SECONDARY_ROOT =
      AbsPath.of(Paths.get("/fake/secondary").toAbsolutePath());
  private static final WatchmanWatcherQuery FAKE_SECONDARY_QUERY =
      ImmutableWatchmanWatcherQuery.ofImpl(
          new WatchRoot("/fake/SECONDARY", true),
          "fake-expr",
          ImmutableList.of(),
          ForwardRelPath.EMPTY);

  private BuckEventBus eventBus = BuckEventBusForTests.newInstance(FakeClock.doNotCare());
  private HgCmdLineInterface HGCmd = EasyMock.createMock(HgCmdLineInterface.class);
  private EventBuffer eventBuffer;

  @Rule public final TemporaryFolder tmpDir = new TemporaryFolder();

  @Before
  public void setUp() {
    eventBuffer = new EventBuffer();
    eventBus.register(eventBuffer);
  }

  @Test
  public void testWatchmanRecordIsEmpty()
      throws VersionControlCommandFailedException, InterruptedException {
    ImmutableMap<String, Object> watchmanOutput =
        ImmutableMap.of(
            "version",
            "2.9.2",
            "clock",
            "c:1386170113:26390:5:50273",
            "is_fresh_instance",
            false,
            "files",
            ImmutableList.of());
    ImmutableSet<String> fileChangesFromHg = ImmutableSet.of("M folly/range.h", "A test/hello.h");

    WatchmanRecordChecker watchmanRecordChecker = createWatchmanRecordChecker(watchmanOutput);
    EasyMock.expect(HGCmd.currentRevisionId()).andReturn(BASE_COMMIT);
    EasyMock.expect(HGCmd.changedFiles(BASE_COMMIT)).andReturn(fileChangesFromHg);
    EasyMock.replay(HGCmd);

    watchmanRecordChecker.checkFileChanges(eventBus);
    assertEquals(1, eventBuffer.events.size());
    // Check the file change event, we are using the Hg result as we don't have a
    // watchmanCursorRecord
    WatchmanStatusEvent.FileChangesSinceLastBuild fileChangeEvent = eventBuffer.events.get(0);
    assertEquals(2, fileChangeEvent.getFileChangeList().size());
    assertEquals(
        "filePath=folly/range.h", fileChangeEvent.getFileChangeList().get(0).getFilePath());
    assertEquals("filePath=test/hello.h", fileChangeEvent.getFileChangeList().get(1).getFilePath());
    assertEquals(2, fileChangeEvent.getFileChangeList().size());
  }

  @Test
  public void testHgCommitBaseChanged()
      throws VersionControlCommandFailedException, InterruptedException {
    ImmutableMap<String, Object> watchmanOutput =
        ImmutableMap.of(
            "version",
            "2.9.2",
            "clock",
            "c:1386170113:26390:5:50273",
            "is_fresh_instance",
            false,
            "files",
            ImmutableList.of());
    ImmutableSet<String> fileChangesFromHg = ImmutableSet.of("M folly/range.h", "A test/hello.h");
    // create a existing watchmanRecord
    String oldCommit = "oldCommit";
    WatchmanCursorRecord cursorRecord = new WatchmanCursorRecord("c:0:0", oldCommit);
    WatchmanCursorRecorder recorder = new WatchmanCursorRecorder(tmpDir.getRoot().toPath());
    recorder.recordWatchmanCursor(cursorRecord);

    // update the local watchman cursor
    recorder.recordWatchmanCursor(cursorRecord);
    WatchmanRecordChecker watchmanRecordChecker = createWatchmanRecordChecker(watchmanOutput);
    EasyMock.expect(HGCmd.currentRevisionId()).andReturn(BASE_COMMIT);
    EasyMock.expect(HGCmd.changedFiles(BASE_COMMIT)).andReturn(fileChangesFromHg);
    EasyMock.replay(HGCmd);

    watchmanRecordChecker.checkFileChanges(eventBus);
    assertEquals(1, eventBuffer.events.size());
    // Check the file change event, we are expect to use the Hg result as we changed the hg base
    WatchmanStatusEvent.FileChangesSinceLastBuild fileChangeEvent = eventBuffer.events.get(0);
    assertEquals(2, fileChangeEvent.getFileChangeList().size());
    assertEquals(
        "filePath=folly/range.h", fileChangeEvent.getFileChangeList().get(0).getFilePath());
    assertEquals("filePath=test/hello.h", fileChangeEvent.getFileChangeList().get(1).getFilePath());
    assertEquals(2, fileChangeEvent.getFileChangeList().size());
  }

  @Test
  public void testFileChangesFromWatchmanCursor()
      throws VersionControlCommandFailedException, InterruptedException {
    ImmutableMap<String, Object> watchmanRootOutput =
        ImmutableMap.of(
            "files", ImmutableList.of(ImmutableMap.<String, Object>of("name", "foo/bar/baz")));
    WatchmanClient client =
        new FakeWatchmanClient(0, ImmutableMap.of(FAKE_CLOCK_QUERY, watchmanRootOutput));
    WatchmanRecordChecker watchmanRecordChecker =
        new WatchmanRecordChecker(
            client,
            TimeUnit.SECONDS.toNanos(10),
            TimeUnit.SECONDS.toNanos(1),
            ImmutableMap.of(FAKE_ROOT, FAKE_QUERY),
            tmpDir.getRoot().toPath(),
            HGCmd /* timeout */);

    ImmutableSet<String> fileChangesFromHg = ImmutableSet.of("M folly/range.h", "A test/hello.h");

    // create a existing watchmanRecord
    WatchmanCursorRecord cursorRecord = new WatchmanCursorRecord("c:0:0", BASE_COMMIT);
    WatchmanCursorRecorder recorder = new WatchmanCursorRecorder(tmpDir.getRoot().toPath());
    recorder.recordWatchmanCursor(cursorRecord);

    // update the local watchman cursor
    recorder.recordWatchmanCursor(cursorRecord);

    EasyMock.expect(HGCmd.currentRevisionId()).andReturn(BASE_COMMIT);
    EasyMock.expect(HGCmd.changedFiles(BASE_COMMIT)).andReturn(fileChangesFromHg);
    EasyMock.replay(HGCmd);

    watchmanRecordChecker.checkFileChanges(eventBus);
    Thread.sleep(100);
    assertEquals(1, eventBuffer.events.size());
    // Check the file change event, we are expect to use the watchman result
    WatchmanStatusEvent.FileChangesSinceLastBuild fileChangeEvent = eventBuffer.events.get(0);
    assertEquals(1, fileChangeEvent.getFileChangeList().size());
    assertEquals("filePath=foo/bar/baz", fileChangeEvent.getFileChangeList().get(0).getFilePath());
    assertEquals(1, fileChangeEvent.getFileChangeList().size());
  }

  private WatchmanRecordChecker createWatchmanRecordChecker(ImmutableMap<String, Object> response) {
    return new WatchmanRecordChecker(
        new FakeWatchmanClient(
            0 /* queryElapsedTimeNanos */, ImmutableMap.of(FAKE_UUID_QUERY, response)),
        TimeUnit.SECONDS.toNanos(5),
        0,
        ImmutableMap.of(
            FAKE_ROOT, FAKE_QUERY,
            FAKE_SECONDARY_ROOT, FAKE_SECONDARY_QUERY),
        tmpDir.getRoot().toPath(),
        HGCmd /* timeout */);
  }

  private static class EventBuffer {
    public final List<WatchmanStatusEvent.FileChangesSinceLastBuild> events = new ArrayList<>();

    @Subscribe
    public void on(WatchmanStatusEvent.FileChangesSinceLastBuild event) {
      events.add(event);
    }
  }
}
