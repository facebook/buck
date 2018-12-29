/*
 * Copyright 2018-present Facebook, Inc.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.string.MoreStrings;
import com.facebook.buck.util.timing.FakeClock;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Test;

public class RenderingConsoleTest {
  TestConsole console = new TestConsole();
  RenderingConsole renderingConsole = new RenderingConsole(FakeClock.doNotCare(), console);
  int lastNumLinesRead = 0;

  @After
  public void tearDown() {
    renderingConsole.close();
  }

  Iterable<String> getNewLinesFromStderr() throws IOException {
    String text = console.getTextWrittenToStdErr();
    ImmutableList<String> allLines = MoreStrings.lines(text);
    ImmutableList<String> newLines = allLines.subList(lastNumLinesRead, allLines.size());
    lastNumLinesRead = allLines.size();
    return newLines;
  }

  public void assertStderrEquals(String... lines) throws IOException {
    MoreAsserts.assertIterablesEquals(ImmutableList.copyOf(lines), getNewLinesFromStderr());
  }

  @Test
  public void testUnconfiguredRenderingConsolePrintsDirectly() throws IOException {
    renderingConsole.logLines("line1", "line2");
    assertStderrEquals("line1", "line2");

    renderingConsole.logLines("line3", "line4");
    assertStderrEquals("line3", "line4");
  }

  @Test
  public void testRenderingConsoleRenders() throws IOException {
    List<String> lines = new ArrayList<>();
    renderingConsole.registerDelegate(currentTimeMillis -> ImmutableList.copyOf(lines));

    renderingConsole.render();
    assertStderrEquals();

    lines.add("super1");
    lines.add("super2");
    renderingConsole.render();
    assertStderrEquals("super1", "super2");

    lines.clear();
    lines.add("super1");
    renderingConsole.render();
    assertStderrEquals("super1", "");
  }

  @Test
  public void testPrintedLinesAppearBeforeRenderedFrame() throws IOException {
    List<String> lines = new ArrayList<>();
    renderingConsole.registerDelegate(currentTimeMillis -> ImmutableList.copyOf(lines));
    // we just want it started, we don't want it to actually render except exactly when we want.
    renderingConsole.setIsRendering(true);

    renderingConsole.logLines("log1", "log2");
    // log lines shouldn't appear until the next render.
    assertStderrEquals();

    lines.add("super1");
    renderingConsole.render();
    assertStderrEquals("log1", "log2", "super1");
    renderingConsole.logLines("log3");
    renderingConsole.render();
    assertStderrEquals("log3", "super1");
    renderingConsole.render();
    assertStderrEquals("super1");
  }

  @Test
  public void testRenderingIsDisabledWhenConsoleIsUsedDirectly() throws Exception {
    renderingConsole.registerDelegate(currentTimeMillis -> ImmutableList.of("super1", "super2"));
    // we just want it started, we don't want it to actually render except exactly when we want.
    renderingConsole.setIsRendering(true);

    renderingConsole.logLines("early");
    renderingConsole.render();
    assertStderrEquals("early", "super1", "super2");
    assertTrue(renderingConsole.isRendering());

    renderingConsole.logLines("before");
    console.getStdErr().println("breaking");
    renderingConsole.logLines("after");

    renderingConsole.render();
    assertFalse(renderingConsole.isRendering());

    renderingConsole.logLines("postStop");
    // TODO(cjhopman): It's a bug that "before" and "after" get lost.
    assertStderrEquals("breaking", "postStop");
  }

  @Test
  public void testRenderingIsDisabledOnPrintToStdout() throws IOException {
    renderingConsole.registerDelegate(currentTimeMillis -> ImmutableList.of("super1", "super2"));
    // we just want it started, we don't want it to actually render except exactly when we want.
    renderingConsole.setIsRendering(true);

    renderingConsole.logLines("early");
    renderingConsole.render();
    assertStderrEquals("early", "super1", "super2");
    assertTrue(renderingConsole.isRendering());

    renderingConsole.logLines("before");
    renderingConsole.printToStdOut("stdout");
    renderingConsole.logLines("after");

    renderingConsole.render();
    assertFalse(renderingConsole.isRendering());

    renderingConsole.logLines("postStop");
    // printToStdout does a graceful shutdown and renders one extra line.
    assertStderrEquals("before", "super1", "super2", "after", "postStop");

    assertEquals(console.getTextWrittenToStdOut(), "stdout" + System.lineSeparator());
  }

  // TODO(cjhopman): It should be the case that printing to System.err directly should be handled
  // correctly, but it isn't. Once it is, add a test for it.
}
