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

package com.facebook.buck.util;

import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.createStrictMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.eventbus.EventBus;

import org.easymock.Capture;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;

public class WatchmanWatcherTest {

  @After
  public void cleanUp() {
    // Clear interrupted state so it doesn't affect any other test.
    Thread.interrupted();
  }

  @Test
  public void whenFilesListIsEmptyThenNoEventsAreGenerated()
      throws IOException, InterruptedException {
    String watchmanOutput = Joiner.on('\n').join(
        "{",
        "\"version\": \"2.9.2\",",
        "\"clock\": \"c:1386170113:26390:5:50273\",",
        "\"is_fresh_instance\": false,",
        "\"files\": []",
        "}");
    EventBus eventBus = createStrictMock(EventBus.class);
    Process process = createWaitForProcessMock(watchmanOutput);
    replay(eventBus, process);
    WatchmanWatcher watcher = createWatcher(eventBus, process);
    watcher.postEvents();
    verify(eventBus, process);
  }

  @Test
  public void whenNameThenModifyEventIsGenerated() throws IOException, InterruptedException {
    String watchmanOutput = Joiner.on('\n').join(
        "{\"files\": [",
            "{",
                "\"name\": \"foo/bar/baz\"",
            "}",
        "]}");
    Capture<WatchEvent<Path>> eventCapture = new Capture<>();
    EventBus eventBus = createStrictMock(EventBus.class);
    eventBus.post(capture(eventCapture));
    Process process = createWaitForProcessMock(watchmanOutput);
    replay(eventBus, process);
    WatchmanWatcher watcher = createWatcher(eventBus, process);
    watcher.postEvents();
    verify(eventBus, process);
    assertEquals("Should be modify event.",
        StandardWatchEventKinds.ENTRY_MODIFY,
        eventCapture.getValue().kind());
    assertEquals("Path should match watchman output.",
        "foo/bar/baz",
        eventCapture.getValue().context().toString());
  }

  @Test
  public void whenNewIsTrueThenCreateEventIsGenerated() throws IOException, InterruptedException {
    String watchmanOutput = Joiner.on('\n').join(
        "{\"files\": [",
            "{",
                "\"name\": \"foo/bar/baz\",",
                "\"new\": true",
            "}",
        "]}");
    Capture<WatchEvent<Path>> eventCapture = new Capture<>();
    EventBus eventBus = createStrictMock(EventBus.class);
    eventBus.post(capture(eventCapture));
    Process process = createWaitForProcessMock(watchmanOutput);
    replay(eventBus, process);
    WatchmanWatcher watcher = createWatcher(eventBus, process);
    watcher.postEvents();
    verify(eventBus, process);
    assertEquals("Should be create event.",
        StandardWatchEventKinds.ENTRY_CREATE,
        eventCapture.getValue().kind());
  }

  @Test
  public void whenExistsIsFalseThenDeleteEventIsGenerated()
      throws IOException, InterruptedException {
    String watchmanOutput = Joiner.on('\n').join(
        "{\"files\": [",
            "{",
                "\"name\": \"foo/bar/baz\",",
                "\"exists\": false",
            "}",
        "]}");
    Capture<WatchEvent<Path>> eventCapture = new Capture<>();
    EventBus eventBus = createStrictMock(EventBus.class);
    eventBus.post(capture(eventCapture));
    Process process = createWaitForProcessMock(watchmanOutput);
    replay(eventBus, process);
    WatchmanWatcher watcher = createWatcher(eventBus, process);
    watcher.postEvents();
    verify(eventBus, process);
    assertEquals("Should be delete event.",
        StandardWatchEventKinds.ENTRY_DELETE,
        eventCapture.getValue().kind());
  }

  @Test
  public void whenNewAndNotExistsThenDeleteEventIsGenerated()
      throws IOException, InterruptedException {
    String watchmanOutput = Joiner.on('\n').join(
        "{\"files\": [",
            "{",
                "\"name\": \"foo/bar/baz\",",
                "\"new\": true,",
                "\"exists\": false",
             "}",
        "]}");
    Capture<WatchEvent<Path>> eventCapture = new Capture<>();
    EventBus eventBus = createStrictMock(EventBus.class);
    eventBus.post(capture(eventCapture));
    Process process = createWaitForProcessMock(watchmanOutput);
    replay(eventBus, process);
    WatchmanWatcher watcher = createWatcher(eventBus, process);
    watcher.postEvents();
    verify(eventBus, process);
    assertEquals("Should be delete event.",
        StandardWatchEventKinds.ENTRY_DELETE,
        eventCapture.getValue().kind());
  }

  @Test
  public void whenMultipleFilesThenMultipleEventsGenerated()
      throws IOException, InterruptedException {
    String watchmanOutput = Joiner.on('\n').join(
        "{\"files\": [",
            "{",
                "\"name\": \"foo/bar/baz\"",
            "},",
            "{",
                "\"name\": \"foo/bar/boz\"",
            "}",
        "]}");
    EventBus eventBus = createStrictMock(EventBus.class);
    Capture<WatchEvent<Path>> firstEvent = new Capture<>();
    Capture<WatchEvent<Path>> secondEvent = new Capture<>();
    eventBus.post(capture(firstEvent));
    eventBus.post(capture(secondEvent));
    Process process = createWaitForProcessMock(watchmanOutput);
    replay(eventBus, process);
    WatchmanWatcher watcher = createWatcher(eventBus, process);
    watcher.postEvents();
    verify(eventBus, process);
    assertEquals("Path should match watchman output.",
        "foo/bar/baz",
        firstEvent.getValue().context().toString());
    assertEquals("Path should match watchman output.",
        "foo/bar/boz",
        secondEvent.getValue().context().toString());
  }

  @Test
  public void whenTooManyChangesThenOverflowEventGenerated()
      throws IOException, InterruptedException {
    String watchmanOutput = Joiner.on('\n').join(
        "{\"files\": [",
            "{",
                "\"name\": \"foo/bar/baz\"",
            "}",
        "]}");
    Capture<WatchEvent<Path>> eventCapture = new Capture<>();
    EventBus eventBus = createStrictMock(EventBus.class);
    eventBus.post(capture(eventCapture));
    Process process = createProcessMock(watchmanOutput);
    process.destroy();
    expectLastCall();
    replay(eventBus, process);
    WatchmanWatcher watcher = createWatcher(
        eventBus,
        process,
        -1 /* overflow */);
    watcher.postEvents();
    verify(eventBus, process);
    assertEquals("Should be overflow event.",
        StandardWatchEventKinds.OVERFLOW,
        eventCapture.getValue().kind());
  }

  @Test
  public void whenWatchmanFailsThenOverflowEventGenerated()
      throws IOException, InterruptedException {
    String watchmanOutput = "";
    Capture<WatchEvent<Path>> eventCapture = new Capture<>();
    EventBus eventBus = createStrictMock(EventBus.class);
    eventBus.post(capture(eventCapture));
    Process process = createWaitForProcessMock(watchmanOutput, 1);
    replay(eventBus, process);
    WatchmanWatcher watcher = createWatcher(eventBus, process);
    try {
      watcher.postEvents();
      fail("Should have thrown IOException.");
    } catch (WatchmanWatcherException e) {
      assertTrue("Should be watchman error", e.getMessage().startsWith("Watchman failed"));
    }
    verify(eventBus, process);
    assertEquals("Should be overflow event.",
        StandardWatchEventKinds.OVERFLOW,
        eventCapture.getValue().kind());
  }

  @Test
  public void whenWatchmanInterruptedThenOverflowEventGenerated()
      throws IOException, InterruptedException {
    String watchmanOutput = "";
    String message = "Boo!";
    Capture<WatchEvent<Path>> eventCapture = new Capture<>();
    EventBus eventBus = createStrictMock(EventBus.class);
    eventBus.post(capture(eventCapture));
    Process process = createWaitForProcessMock(watchmanOutput, new InterruptedException(message));
    process.destroy();
    replay(eventBus, process);
    WatchmanWatcher watcher = createWatcher(eventBus, process);
    try {
      watcher.postEvents();
    } catch (InterruptedException e) {
      assertEquals("Should be test interruption.", e.getMessage(), message);
    }
    verify(eventBus, process);
    assertTrue(Thread.currentThread().isInterrupted());
    assertEquals("Should be overflow event.",
        StandardWatchEventKinds.OVERFLOW,
        eventCapture.getValue().kind());
  }

  @Test
  public void whenQueryResultContainsErrorThenHumanReadableExceptionThrown()
      throws IOException, InterruptedException {
    String watchmanError = "Watch does not exist.";
    String watchmanOutput = Joiner.on('\n').join(
        "{",
        "\"version\": \"2.9.2\",",
        "\"error\": \"" + watchmanError + "\"",
        "}");
    EventBus eventBus = createStrictMock(EventBus.class);
    Process process = createWaitForProcessMock(watchmanOutput);
    replay(eventBus, process);
    WatchmanWatcher watcher = createWatcher(eventBus, process);
    try {
      watcher.postEvents();
      fail("Should have thrown RuntimeException");
    } catch (RuntimeException e) {
      assertThat("Should contain watchman error.",
          e.getMessage(),
          Matchers.containsString(watchmanError));
    }
  }

  private WatchmanWatcher createWatcher(EventBus eventBus, Process process) {
    return createWatcher(
        eventBus,
        process,
        200 /* overflow */);
  }

  private WatchmanWatcher createWatcher(EventBus eventBus,
                                        Process process,
                                        int overflow) {
    return new WatchmanWatcher(
        Suppliers.ofInstance(process),
        eventBus,
        overflow,
        "" /* query */);
  }

  private Process createProcessMock(String output) {
    Process process = createMock(Process.class);
    expect(process.getInputStream()).andReturn(
        new ByteArrayInputStream(output.getBytes(Charsets.US_ASCII)));
    expect(process.getOutputStream()).andReturn(
        new ByteArrayOutputStream()).times(2);
    return process;
  }

  private Process createWaitForProcessMock(String output) {
    return createWaitForProcessMock(output, 0);
  }

  private Process createWaitForProcessMock(String output, Throwable t) throws InterruptedException {
    Process process = createProcessMock(output);
    expect(process.waitFor()).andThrow(Preconditions.checkNotNull(t));
    return process;
  }

  private Process createWaitForProcessMock(String output, int exitCode) {
    Process process = createProcessMock(output);
    if (exitCode != 0) {
      expect(process.getErrorStream()).andReturn(
          new ByteArrayInputStream("".getBytes(Charsets.US_ASCII)));
    }
    try {
      expect(process.waitFor()).andReturn(exitCode);
    } catch (InterruptedException e) {
      fail("Should not throw exception.");
    }
    return process;
  }
}
