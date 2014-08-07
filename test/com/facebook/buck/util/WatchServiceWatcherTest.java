/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.util;

import static com.facebook.buck.testutil.WatchEvents.createPathEvent;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.createNiceMock;
import static org.easymock.EasyMock.createStrictMock;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;

import com.google.common.collect.Lists;
import com.google.common.eventbus.EventBus;

import org.easymock.Capture;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.FileVisitor;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;

public class WatchServiceWatcherTest {

  private EventBus eventBus;
  private ProjectFilesystem filesystem;
  private WatchService watchService;
  private Path path;
  private WatchKey key;
  private Capture<FileVisitor<Path>> visitor;
  private WatchServiceWatcher watcher;

  @Rule
  public TemporaryFolder tempDir = new TemporaryFolder();

  @Before
  public void setUp() throws IOException {
    eventBus = createStrictMock(EventBus.class);
    filesystem = createNiceMock(ProjectFilesystem.class);
    watchService = createNiceMock(WatchService.class);
    path = createNiceMock(Path.class);
    key = createNiceMock(WatchKey.class);

    expect(filesystem.getRootPath()).andReturn(Paths.get("./someproject/"));
    visitor = new Capture<>();
    filesystem.walkFileTree(anyObject(Path.class), capture(visitor));
    expect(path.register(anyObject(WatchService.class),
        eq(StandardWatchEventKinds.ENTRY_CREATE),
        eq(StandardWatchEventKinds.ENTRY_DELETE),
        eq(StandardWatchEventKinds.ENTRY_MODIFY))).andReturn(key);
  }

  @Test
  public void noEventsPostedWhenNoFilesChange() throws IOException {

    // Return no events when WatchService is polled.
    expect(watchService.poll()).andReturn(null);
    replay(filesystem, eventBus, watchService, path, key);

    // Pump WatchServiceWatcher.
    watcher = new WatchServiceWatcher(
        filesystem, eventBus, watchService);
    visitor.getValue().preVisitDirectory(path, null);
    watcher.postEvents();

    // Check no events were posted to EventBus.
    verify(filesystem, eventBus, watchService, path, key);
  }

  @Test
  public void eventPostedWhenFileChanged() throws IOException {

    // Return a single modify event when WatchService polled.
    expect(watchService.poll()).andReturn(key).andReturn(null);
    expect(filesystem.isPathChangeEvent(anyObject(WatchEvent.class))).andReturn(true).anyTimes();
    expect(filesystem.getRootPath()).andReturn(path).anyTimes();
    expect(key.pollEvents()).andReturn(
        Lists.<WatchEvent<?>>newArrayList(
            createPathEvent(
                Paths.get("./someproject/SomeClass.java"),
                StandardWatchEventKinds.ENTRY_MODIFY)));
    expect(path.relativize(anyObject(Path.class))).andReturn(Paths.get("SomeClass.java"));
    eventBus.post(anyObject(WatchEvent.class));
    expectLastCall().andDelegateTo(new EventBus() {
      @Override
      @SuppressWarnings("unchecked") // Allow downcast from obj to WatchEvent<Path>.
      public void post(Object obj) {
        WatchEvent<Path> event = (WatchEvent<Path>) obj;
        assertEquals(
            "Posted events should have path relative to project root.",
            event.context().toString(),
            "SomeClass.java");
      }
    });
    replay(filesystem, eventBus, watchService, path, key);

    // Pump WatchServiceWatcher.
    watcher = new WatchServiceWatcher(
        filesystem, eventBus, watchService);
    visitor.getValue().preVisitDirectory(path, null);
    watcher.postEvents();

    // Check event was posted to EventBus.
    verify(filesystem, eventBus, watchService, path, key);
  }

  @Test
  public void noEventPostedWhenFileExcluded() throws IOException {

    // Return a single modify event when WatchService polled.
    expect(watchService.poll()).andReturn(key).andReturn(null);
    expect(filesystem.isPathChangeEvent(anyObject(WatchEvent.class))).andReturn(true).anyTimes();
    expect(filesystem.isIgnored(Paths.get("somedir/SomeClass.java"))).andReturn(true);
    expect(filesystem.getRootPath()).andReturn(path).anyTimes();
    expect(key.pollEvents()).andReturn(
        Lists.<WatchEvent<?>>newArrayList(
            createPathEvent(
                Paths.get("./someproject/somedir/SomeClass.java"),
                StandardWatchEventKinds.ENTRY_MODIFY)));
    expect(path.relativize(anyObject(Path.class))).andReturn(Paths.get("somedir/SomeClass.java"));
    replay(filesystem, eventBus, watchService, path, key);

    // Pump WatchServiceWatcher.
    watcher = new WatchServiceWatcher(
        filesystem, eventBus, watchService);
    visitor.getValue().preVisitDirectory(path, null);
    watcher.postEvents();

    // Check no events were posted to EventBus.
    verify(filesystem, eventBus, watchService, path, key);

  }
}
