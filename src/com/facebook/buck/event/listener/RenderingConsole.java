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

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.MoreIterables;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.timing.Clock;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * A console that supports rendering.
 *
 * <p>The Delegate provides the "Super" lines for the rendered frame, log lines will be queued by
 * the RenderingConsole.
 *
 * <p>If no delegate is configured (or if rendering is not running), lines will be logged
 * immediately and won't be queued for the next frame.
 *
 * <p>If something writes to the underlying Console's stderr/stdout streams, rendering will be
 * disabled. If something writes to the real underlying stderr/stdout, we won't notice and it will
 * get overwritten.
 *
 * <p>TODO(cjhopman): For both those stderr/stdout cases, we should just capture the logging and
 * queue it the same as logging that goes explicitly to the RenderingConsole. For real
 * System.err/System.out we might need something a little fancy.
 */
public class RenderingConsole {
  /** The Delegate informs the RenderingConsole what to render. */
  interface Delegate {
    ImmutableList<String> createSuperLinesAtTime(long currentTimeMillis);
  }

  private static final Logger LOG = Logger.get(RenderingConsole.class);
  private static final Duration CONSOLE_RENDER_REFRESH_RATE = Duration.ofMillis(100);

  private final ScheduledExecutorService renderScheduler;

  private final Clock clock;
  private final Console console;
  private final Ansi ansi;

  @Nullable private Delegate delegate = null;
  private final ConcurrentLinkedQueue<String> pendingLogLines = new ConcurrentLinkedQueue<>();
  private int lastNumLinesPrinted = 0;

  private volatile boolean isRendering;

  public RenderingConsole(Clock clock, Console console) {
    this.clock = clock;
    this.console = console;
    this.ansi = console.getAnsi();
    this.renderScheduler =
        Executors.newScheduledThreadPool(
            1,
            new ThreadFactoryBuilder().setNameFormat(getClass().getSimpleName() + "-%d").build());
  }

  public Ansi getAnsi() {
    return ansi;
  }

  public Verbosity getVerbosity() {
    return console.getVerbosity();
  }

  /** Registers a delegate to provide the data to render and starts the rendering thread. */
  public void registerDelegate(Delegate delegate) {
    Preconditions.checkState(this.delegate == null);
    this.delegate = delegate;
  }

  /**
   * Logs the provided lines to stderr. When rendering, this will be queued until the next rendered
   * frame.
   */
  public void logLines(String... lines) {
    logLines(ImmutableList.copyOf(lines));
  }

  /**
   * Logs the provided lines to stderr. When rendering, this will be queued until the next rendered
   * frame.
   */
  public void logLines(Collection<String> lines) {
    if (!isRendering) {
      // If rendering isn't configured, just log the lines directly.
      for (String line : lines) {
        console.getStdErr().getRawStream().println(line);
      }
    } else {
      pendingLogLines.addAll(lines);
    }
  }

  /** This prints to stdout and disables rendering. It shouldn't be used. */
  @Deprecated
  public void printToStdOut(String testOutput) {
    // We're about to write to stop rendering, so make sure we render the final frame before we do.
    render();
    stopRenderScheduler();
    console.getStdOut().println(testOutput);
  }

  @VisibleForTesting
  protected ImmutableList<String> getPendingLogLines() {
    return ImmutableList.copyOf(pendingLogLines);
  }

  @VisibleForTesting
  protected void clearPendingLogLines() {
    pendingLogLines.clear();
  }

  @VisibleForTesting
  synchronized void render() {
    LOG.verbose("Rendering");
    int previousNumLinesPrinted = lastNumLinesPrinted;
    ImmutableList<String> lines = delegate.createSuperLinesAtTime(clock.currentTimeMillis());
    boolean shouldRender = previousNumLinesPrinted != 0 || !lines.isEmpty();

    ImmutableList.Builder<String> logLines = ImmutableList.builder();
    String line;
    while ((line = pendingLogLines.poll()) != null) {
      logLines.add(line);
      shouldRender = true;
    }
    lastNumLinesPrinted = lines.size();

    // Synchronize on the DirtyPrintStreamDecorator to prevent interlacing of output.
    // We don't log immediately so we avoid locking the console handler to avoid deadlocks.
    boolean stderrDirty;
    boolean stdoutDirty;
    // TODO(cjhopman): This synchronization is likely useless.
    synchronized (console.getStdErr()) {
      synchronized (console.getStdOut()) {
        // If another source has written to stderr, stop rendering with the SuperConsole.
        // We need to do this to keep our updates consistent. We don't do this with stdout
        // because we don't use it directly except in a couple of cases, where the
        // synchronization in DirtyPrintStreamDecorator should be sufficient

        // TODO(cjhopman): We should probably only stop rendering the super lines and continue
        // rendering log lines.
        stderrDirty = console.getStdErr().isDirty();
        stdoutDirty = console.getStdOut().isDirty();
        if (stderrDirty || stdoutDirty) {
          stopRenderScheduler();
        } else if (shouldRender) {
          String fullFrame = renderFullFrame(logLines.build(), lines, previousNumLinesPrinted);
          console.getStdErr().getRawStream().print(fullFrame);
        }
      }
    }
    if (stderrDirty) {
      LOG.debug("Stopping console output (stderr was dirty).");
    }
  }

  private String renderFullFrame(
      ImmutableList<String> logLines, ImmutableList<String> lines, int previousNumLinesPrinted) {
    int currentNumLines = lines.size();

    Iterable<String> renderedLines =
        Iterables.concat(
            MoreIterables.zipAndConcat(
                Iterables.cycle(ansi.clearLine()),
                logLines,
                Iterables.cycle(ansi.clearToTheEndOfLine() + System.lineSeparator())),
            ansi.asNoWrap(
                MoreIterables.zipAndConcat(
                    Iterables.cycle(ansi.clearLine()),
                    lines,
                    Iterables.cycle(ansi.clearToTheEndOfLine() + System.lineSeparator()))));

    // Number of lines remaining to clear because of old output once we displayed
    // the new output.
    int remainingLinesToClear =
        previousNumLinesPrinted > currentNumLines ? previousNumLinesPrinted - currentNumLines : 0;

    StringBuilder fullFrame = new StringBuilder();
    // We move the cursor back to the top.
    for (int i = 0; i < previousNumLinesPrinted; i++) {
      fullFrame.append(ansi.cursorPreviousLine(1));
    }
    // We display the new output.
    for (String part : renderedLines) {
      fullFrame.append(part);
    }
    // We clear the remaining lines of the old output.
    for (int i = 0; i < remainingLinesToClear; i++) {
      fullFrame.append(ansi.clearLine());
      fullFrame.append(System.lineSeparator());
    }
    // We move the cursor at the end of the new output.
    for (int i = 0; i < remainingLinesToClear; i++) {
      fullFrame.append(ansi.cursorPreviousLine(1));
    }
    return fullFrame.toString();
  }

  /**
   * Shuts down rendering. If rendering, will print at least one final frame.
   *
   * <p>Logging after calling close() will not be queued.
   */
  public void close() {
    boolean shouldRender = isRendering;
    stopRenderScheduler();
    if (shouldRender) {
      render(); // Ensure final frame is rendered.
    }
  }

  /** Schedules a runnable that updates the console output at a fixed interval. */
  void startRenderScheduler() {
    long renderInterval = CONSOLE_RENDER_REFRESH_RATE.toMillis();
    isRendering = true;
    TimeUnit timeUnit = TimeUnit.MILLISECONDS;
    LOG.debug("Starting render scheduler (interval %d ms)", timeUnit.toMillis(renderInterval));
    renderScheduler.scheduleAtFixedRate(
        () -> {
          try {
            render();
          } catch (Error | RuntimeException e) {
            LOG.error(e, "Rendering exception");
            throw e;
          }
        }, /* initialDelay */
        renderInterval, /* period */
        renderInterval,
        timeUnit);
  }

  void setIsRendering(boolean value) {
    isRendering = value;
  }

  public boolean isRendering() {
    return this.isRendering;
  }

  /** Shuts down the thread pool and cancels the fixed interval runnable. */
  private synchronized void stopRenderScheduler() {
    LOG.debug("Stopping render scheduler");
    renderScheduler.shutdownNow();
    isRendering = false;
  }
}
