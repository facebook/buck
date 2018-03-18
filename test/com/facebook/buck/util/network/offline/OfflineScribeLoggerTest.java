/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.util.network.offline;

import static com.facebook.buck.util.network.offline.OfflineScribeLogger.LOGFILE_PATTERN;
import static com.facebook.buck.util.network.offline.OfflineScribeLogger.LOGFILE_PREFIX;
import static com.facebook.buck.util.network.offline.OfflineScribeLogger.LOGFILE_SUFFIX;
import static org.hamcrest.Matchers.arrayContainingInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.json.ObjectMappers;
import com.facebook.buck.util.network.FakeFailingScribeLogger;
import com.facebook.buck.util.network.ScribeLogger;
import com.facebook.buck.util.types.Pair;
import com.fasterxml.jackson.core.JsonParser;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.ObjectArrays;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.ParametersAreNonnullByDefault;
import org.hamcrest.Matchers;
import org.hamcrest.collection.IsIterableWithSize;
import org.junit.Rule;
import org.junit.Test;

public class OfflineScribeLoggerTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void unsentLinesStoredForOffline() throws Exception {
    final String whitelistedCategory = "whitelisted_category";
    final String whitelistedCategory2 = "whitelisted_category_2";
    final String blacklistedCategory = "blacklisted_category";

    final ImmutableList<String> blacklistCategories = ImmutableList.of(blacklistedCategory);
    final int maxScribeOfflineLogsKB = 7;
    ProjectFilesystem filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
    Path logDir = filesystem.getBuckPaths().getOfflineLogDir();
    String[] ids = {"test1", "test2", "test3", "test4"};

    char[] longLineBytes = new char[1000];
    Arrays.fill(longLineBytes, 'A');
    String longLine = new String(longLineBytes);
    char[] tooLongLineBytes = new char[6000];
    Arrays.fill(longLineBytes, 'A');
    String tooLongLine = new String(tooLongLineBytes);

    // As we set max space for logs to 7KB, then we expect storing data with 2 'longLine' (which,
    // given UTF-8 is used, will be ~ 2 * 1KB) to succeed. We then expect subsequent attempt to
    // store data with 'tooLongLine' (~6KB) to fail. We also expect that logfile created by first
    // fakeLogger ('test1' id) will be removed as otherwise data from last logger would not fit.
    //
    // Note that code sending already stored offline logs will be triggered by the first log() as
    // it succeeds, but further failing log() (and initiating storing) will stop sending. Hence, no
    // logs will be deleted due to the sending routine.
    FakeFailingOfflineScribeLogger fakeLogger = null;
    for (String id : ids) {
      fakeLogger =
          new FakeFailingOfflineScribeLogger(
              blacklistCategories, maxScribeOfflineLogsKB, filesystem, logDir, new BuildId(id));

      // Simulate network issues occurring for some of sending attempts (all after first one).

      // Logging succeeds.
      fakeLogger.log(whitelistedCategory, ImmutableList.of("hello world 1", "hello world 2"));
      // Logging fails.
      fakeLogger.log(whitelistedCategory, ImmutableList.of("hello world 3", "hello world 4"));
      // Event with blacklisted category for offline logging.
      fakeLogger.log(blacklistedCategory, ImmutableList.of("hello world 5", "hello world 6"));
      // Logging fails.
      fakeLogger.log(whitelistedCategory2, ImmutableList.of(longLine, longLine));
      // Logging fails, but offline logging rejects data as well - too big.
      fakeLogger.log(whitelistedCategory2, ImmutableList.of(tooLongLine));

      fakeLogger.close();
    }

    // Check correct logs are in the directory (1st log removed).
    Path[] expectedLogPaths =
        Arrays.stream(ids)
            .map(id -> filesystem.resolve(logDir.resolve(LOGFILE_PREFIX + id + LOGFILE_SUFFIX)))
            .toArray(Path[]::new);

    ImmutableSortedSet<Path> logs =
        filesystem.getMtimeSortedMatchingDirectoryContents(logDir, LOGFILE_PATTERN);
    assertThat(
        logs,
        Matchers.allOf(
            hasItem(expectedLogPaths[1]),
            hasItem(expectedLogPaths[2]),
            hasItem(expectedLogPaths[3]),
            IsIterableWithSize.iterableWithSize(3)));

    // Check that last logger logged correct data.
    assertEquals(3, fakeLogger.getAttemptStoringCategoriesWithLinesCount());

    String[] whitelistedCategories = {whitelistedCategory, whitelistedCategory2};
    String[][] whitelistedLines = {{"hello world 3", "hello world 4"}, {longLine, longLine}};

    try (JsonParser jsonParser = ObjectMappers.createParser(fakeLogger.getStoredLog())) {
      Iterator<ScribeData> it = ObjectMappers.READER.readValues(jsonParser, ScribeData.class);
      int dataNum = 0;
      try {
        while (it.hasNext()) {
          assertTrue(dataNum < 2);

          ScribeData data = it.next();
          assertThat(data.getCategory(), is(whitelistedCategories[dataNum]));
          assertThat(
              data.getLines(),
              Matchers.allOf(
                  hasItem(whitelistedLines[dataNum][0]),
                  hasItem(whitelistedLines[dataNum][1]),
                  IsIterableWithSize.iterableWithSize(2)));

          dataNum++;
        }
      } catch (Exception e) {
        fail("Reading stored offline log failed.");
      }
      assertEquals(2, dataNum);
    } catch (Exception e) {
      fail("Obtaining iterator for reading the log failed.");
    }
  }

  @Test
  public void sendStoredLogs() throws Exception {
    ImmutableList<String> blacklistCategories = ImmutableList.of();
    int maxScribeOfflineLogsKB = 2;
    ProjectFilesystem filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
    Path logDir = filesystem.getBuckPaths().getOfflineLogDir();
    String[] ids = {"test1", "test2", "test3"};
    String[] uniqueCategories = {"cat1", "cat2"};
    String[] categories = {uniqueCategories[0], uniqueCategories[1], uniqueCategories[0]};
    final String testCategory = "test_category";
    final String line = "XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX";
    List<Pair<String, Iterable<String>>> sentData = new ArrayList<>();

    ScribeLogger succeeddingLogger =
        new ScribeLogger() {
          @Override
          public ListenableFuture<Void> log(String category, Iterable<String> lines) {
            if (!category.equals(testCategory)) {
              sentData.add(new Pair<>(category, lines));
            }
            return Futures.immediateFuture(null);
          }

          @Override
          public void close() {}
        };

    // Create 3 dummy logfiles - each will have 3 categories x 4 lines ~ 0.9KB. Hence, when reading
    // and sending the logger should stop after 2 of those 3 files (we set the limit to 2KB).
    filesystem.mkdirs(logDir);
    for (String id : ids) {
      File log = filesystem.resolve(logDir.resolve(LOGFILE_PREFIX + id + LOGFILE_SUFFIX)).toFile();
      BufferedOutputStream logFileStoreStream = new BufferedOutputStream(new FileOutputStream(log));
      for (String category : categories) {
        byte[] scribeData =
            ObjectMappers.WRITER
                .writeValueAsString(
                    ScribeData.builder()
                        .setCategory(category)
                        .setLines(ImmutableList.of(line, line, line, line))
                        .build())
                .getBytes(Charsets.UTF_8);
        logFileStoreStream.write(scribeData);
      }
      logFileStoreStream.close();
    }

    // Get the logger and trigger sending with dummy succeeding log().
    OfflineScribeLogger offlineLogger =
        new OfflineScribeLogger(
            succeeddingLogger,
            blacklistCategories,
            maxScribeOfflineLogsKB,
            filesystem,
            BuckEventBusForTests.newInstance(),
            new BuildId("sendingLogger"));
    offlineLogger.log(testCategory, ImmutableList.of("line1", "line2"));
    offlineLogger.close();

    // Check read&sent data is as expected - for first category we expect clustered 8 lines from
    // 2x4.
    assertEquals(4, sentData.size());
    String[] expectedCategories =
        ObjectArrays.concat(uniqueCategories, uniqueCategories, String.class);
    String[] seenCategories = new String[sentData.size()];
    for (int i = 0; i < sentData.size(); i++) {
      seenCategories[i] = sentData.get(i).getFirst();
      int expectedCount = (sentData.get(i).getFirst().equals(uniqueCategories[0])) ? 8 : 4;
      assertThat(
          sentData.get(i).getSecond(),
          Matchers.allOf(
              everyItem(equalTo(line)),
              IsIterableWithSize.<String>iterableWithSize(expectedCount)));
    }
    assertThat(seenCategories, arrayContainingInAnyOrder(expectedCategories));

    // Check that oldest log was not removed (due to exceeding the byte limit when reading&sending).
    ImmutableSortedSet<Path> logs =
        filesystem.getMtimeSortedMatchingDirectoryContents(logDir, LOGFILE_PATTERN);
    Path notRemovedLog =
        filesystem.resolve(logDir.resolve(LOGFILE_PREFIX + ids[0] + LOGFILE_SUFFIX));
    assertThat(
        logs, Matchers.allOf(hasItem(notRemovedLog), IsIterableWithSize.iterableWithSize(1)));
  }

  /**
   * Fake implementation of {@link OfflineScribeLogger} which fails to log after the first attempt
   * (which succeeds) and allows for checking count of stored categories with lines.
   */
  private final class FakeFailingOfflineScribeLogger extends ScribeLogger {

    private final ImmutableList<String> blacklistCategories;
    private final BuildId id;
    private final ProjectFilesystem filesystem;
    private final Path logDir;
    private final OfflineScribeLogger offlineScribeLogger;
    private final AtomicInteger storedCategoriesWithLines;

    FakeFailingOfflineScribeLogger(
        ImmutableList<String> blacklistCategories,
        int maxScribeOfflineLogs,
        ProjectFilesystem filesystem,
        Path logDir,
        BuildId id) {
      this.blacklistCategories = blacklistCategories;
      this.id = id;
      this.filesystem = filesystem;
      this.logDir = logDir;
      this.offlineScribeLogger =
          new OfflineScribeLogger(
              new FakeFailingScribeLogger(),
              blacklistCategories,
              maxScribeOfflineLogs,
              filesystem,
              BuckEventBusForTests.newInstance(),
              id);
      this.storedCategoriesWithLines = new AtomicInteger(0);
    }

    @Override
    public ListenableFuture<Void> log(String category, Iterable<String> lines) {
      ListenableFuture<Void> upload = offlineScribeLogger.log(category, lines);
      Futures.addCallback(
          upload,
          new FutureCallback<Void>() {
            @Override
            public void onSuccess(Void result) {}

            @Override
            @ParametersAreNonnullByDefault
            public void onFailure(Throwable t) {
              if (!blacklistCategories.contains(category)) {
                storedCategoriesWithLines.incrementAndGet();
              }
            }
          },
          MoreExecutors.directExecutor());
      return upload;
    }

    @Override
    public void close() throws IOException {
      offlineScribeLogger.close();
    }

    int getAttemptStoringCategoriesWithLinesCount() {
      return storedCategoriesWithLines.get();
    }

    Path getStoredLog() {
      return filesystem.resolve(logDir.resolve(LOGFILE_PREFIX + id + LOGFILE_SUFFIX));
    }
  }
}
