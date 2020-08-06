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

package com.facebook.buck.core.parser;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.graph.transformation.impl.GraphComputationStage;
import com.facebook.buck.core.parser.buildtargetpattern.BuildTargetPattern;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.filesystem.ProjectFilesystemView;
import com.facebook.buck.io.watchman.FileSystemNotWatchedException;
import com.facebook.buck.io.watchman.ProjectWatch;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.io.watchman.WatchmanClient;
import com.facebook.buck.io.watchman.WatchmanFactory;
import com.facebook.buck.io.watchman.WatchmanNotFoundException;
import com.facebook.buck.io.watchman.WatchmanQueryFailedException;
import com.facebook.buck.io.watchman.WatchmanQueryTimedOutException;
import com.facebook.buck.io.watchman.WatchmanTestDaemon;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ListeningProcessExecutor;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.timing.DefaultClock;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import junitparams.JUnitParamsRunner;
import org.hamcrest.core.IsInstanceOf;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(JUnitParamsRunner.class)
public class WatchmanBuildPackageComputationTest extends AbstractBuildPackageComputationTest {

  private static final Logger LOG = Logger.get(WatchmanBuildPackageComputationTest.class);

  @Rule public TemporaryPaths watchmanStateDirectory = new TemporaryPaths();

  private Clock clock;
  private Console console;
  private WatchmanTestDaemon watchmanDaemon;

  @Before
  public void setUpWatchman() throws IOException, InterruptedException {
    clock = new DefaultClock();
    console = new TestConsole();

    try {
      watchmanDaemon =
          WatchmanTestDaemon.start(
              watchmanStateDirectory.getRoot(), new ListeningProcessExecutor());
    } catch (WatchmanNotFoundException e) {
      Assume.assumeNoException(e);
    }
  }

  @After
  public void tearDownWatchman() throws IOException {
    if (watchmanDaemon != null) {
      watchmanDaemon.close();
    }
  }

  @Test
  public void findsBuildFilesInWatchmanProject()
      throws ExecutionException, IOException, InterruptedException {
    filesystem.mkdirs(Paths.get("project/dir"));
    filesystem.createNewFile(Paths.get("project/dir/BUCK"));

    try (WatchmanClient client = createWatchmanClient()) {
      long watchTimeoutNanos = TimeUnit.SECONDS.toNanos(5);
      client.queryWithTimeout(watchTimeoutNanos, "watch", filesystem.getRootPath().toString());
    }
    ProjectFilesystemView projectFilesystemView =
        filesystem.asView().withView(Paths.get("project"), ImmutableSet.of());
    ImmutableSet<AbsPath> watchedProjects =
        ImmutableSet.of(AbsPath.of(filesystem.resolve("project")));
    BuildPackagePaths paths =
        transform(
            key(CanonicalCellName.rootCell(), BuildTargetPattern.Kind.PACKAGE, "dir", ""),
            getComputationStages("BUCK", projectFilesystemView, watchedProjects));

    assertEquals(ImmutableSortedSet.of(Paths.get("dir")), paths.getPackageRoots());
  }

  @Test
  public void fileSystemMustBeWatchedByWatchman() throws IOException {
    filesystem.mkdirs(Paths.get("project"));

    ProjectFilesystemView projectFilesystemView =
        filesystem.asView().withView(Paths.get("project"), ImmutableSet.of());
    ImmutableSet<AbsPath> watchedProjects = ImmutableSet.of(filesystem.getRootPath());

    thrown.expect(IsInstanceOf.instanceOf(FileSystemNotWatchedException.class));
    getComputationStages("BUCK", projectFilesystemView, watchedProjects);
  }

  @Test
  public void watchmanMustNotBeNullWatchman() throws IOException {
    filesystem.mkdirs(Paths.get("project"));

    thrown.expect(IsInstanceOf.instanceOf(FileSystemNotWatchedException.class));
    new WatchmanBuildPackageComputation("BUCK", filesystem.asView(), WatchmanFactory.NULL_WATCHMAN);
  }

  @Test
  public void throwsIfWatchmanTimesOut() throws ExecutionException, InterruptedException {
    Watchman stubWatchmanFactory =
        createMockWatchmanFactory((long timeoutNanos, Object... query) -> Optional.empty());

    thrown.expect(ExecutionException.class);
    thrown.expectCause(IsInstanceOf.instanceOf(WatchmanQueryTimedOutException.class));
    transform(
        key(CanonicalCellName.rootCell(), BuildTargetPattern.Kind.PACKAGE, "", ""),
        getComputationStages("BUCK", filesystem.asView(), stubWatchmanFactory));
  }

  @Test
  public void throwsIfWatchmanQueryFails() throws ExecutionException, InterruptedException {
    Watchman stubWatchmanFactory =
        createMockWatchmanFactory(
            (long timeoutNanos, Object... query) -> {
              LOG.info("Processing query: %s", query);
              if (query.length >= 2 && query[0].equals("query")) {
                return Optional.of(
                    ImmutableMap.of(
                        "version",
                        "4.9.4",
                        "error",
                        String.format(
                            "RootResolveError: unable to resolve root %s: directory %s not watched",
                            query[1], query[1])));

              } else {
                throw new RuntimeException("Watchman query not implemented");
              }
            });

    thrown.expect(ExecutionException.class);
    thrown.expectCause(IsInstanceOf.instanceOf(WatchmanQueryFailedException.class));
    transform(
        key(CanonicalCellName.rootCell(), BuildTargetPattern.Kind.PACKAGE, "", ""),
        getComputationStages("BUCK", filesystem.asView(), stubWatchmanFactory));
  }

  @Override
  protected ImmutableList<GraphComputationStage<?, ?>> getComputationStages(String buildFileName) {
    return getComputationStages(
        buildFileName, filesystem.asView(), ImmutableSet.of(AbsPath.of(tmp.getRoot())));
  }

  private ImmutableList<GraphComputationStage<?, ?>> getComputationStages(
      String buildFileName,
      ProjectFilesystemView filesystemView,
      ImmutableSet<AbsPath> watchedProjects) {
    Watchman watchman;
    try {
      watchman = createWatchmanClientFactory(watchedProjects);
    } catch (IOException | InterruptedException e) {
      throw new RuntimeException(e);
    }
    return getComputationStages(buildFileName, filesystemView, watchman);
  }

  @Nonnull
  private ImmutableList<GraphComputationStage<?, ?>> getComputationStages(
      String buildFileName, ProjectFilesystemView filesystemView, Watchman watchman) {
    return ImmutableList.of(
        new GraphComputationStage<>(
            new WatchmanBuildPackageComputation(buildFileName, filesystemView, watchman)));
  }

  private Watchman createWatchmanClientFactory(ImmutableSet<AbsPath> watchedProjects)
      throws IOException, InterruptedException {
    long connectTimeoutNanos = TimeUnit.SECONDS.toNanos(5);
    long endTimeNanos = clock.nanoTime() + connectTimeoutNanos;
    return WatchmanFactory.getWatchman(
        createWatchmanClient(),
        watchmanDaemon.getTransportPath(),
        watchedProjects,
        console,
        clock,
        endTimeNanos);
  }

  @Nonnull
  private WatchmanClient createWatchmanClient() throws IOException {
    return WatchmanFactory.createWatchmanClient(watchmanDaemon.getTransportPath(), console, clock);
  }

  MockWatchmanFactory createMockWatchmanFactory(QueryWithTimeoutFunction mockQueryWithTimeout) {
    return new MockWatchmanFactory() {
      @Override
      public WatchmanClient createClient() {
        return new WatchmanClient() {
          @Override
          public void close() {}

          @Override
          public Optional<? extends Map<String, ?>> queryWithTimeout(
              long timeoutNanos, Object... query) throws IOException, InterruptedException {
            return mockQueryWithTimeout.apply(timeoutNanos, query);
          }
        };
      }
    };
  }

  abstract class MockWatchmanFactory extends Watchman {
    public MockWatchmanFactory() {
      super(
          ImmutableMap.of(
              AbsPath.of(tmp.getRoot()),
              ProjectWatch.of(tmp.getRoot().toString(), Optional.empty())),
          ImmutableSet.of(),
          ImmutableMap.of(),
          Optional.of(Paths.get("(MockWatchmanFactory socket)")),
          "");
    }
  }

  @FunctionalInterface
  interface QueryWithTimeoutFunction {
    Optional<? extends Map<String, ?>> apply(long timeoutNanos, Object... query)
        throws IOException, InterruptedException;
  }
}
