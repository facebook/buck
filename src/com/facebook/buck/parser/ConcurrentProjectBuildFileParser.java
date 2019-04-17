/*
 * Copyright 2019-present Facebook, Inc.
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

package com.facebook.buck.parser;

import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.api.ProjectBuildFileParser;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.skylark.io.GlobSpecWithResult;
import com.facebook.buck.util.CloseableWrapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * Add synchronization layer over existing {@link ProjectBuildFileParser} by creating and
 * maintaining a pool of {@link ProjectBuildFileParser}'s instances.
 *
 * <p>This {@link PythonDslProjectBuildFileParser} wrapper creates new instances of delegated parser
 * using a provided factory and adds them to worker pool. If free parser is available at the time a
 * request is made, it is reused, if not then it is recreated. Once parsing request (aka {@link
 * ProjectBuildFileParser#getBuildFileManifest(Path)} is complete, parser is returned to the worker
 * pool. Worker pool of parsers can grow unconditionally so it is really up to the user of this
 * class to manage concurrency level by calling this class' methods appropriate number of times in
 * parallel.
 *
 * <p>Note that {@link ConcurrentProjectBuildFileParser#reportProfile()} and {@link
 * ConcurrentProjectBuildFileParser#close()} are not synchronized with the worker pool and just call
 * appropriate methods from all the parsers created to the moment. They should only be called when
 * no parsing is in effect.
 *
 * <p>The main reason for this class is to overcome limitations of current implementation of {@link
 * PythonDslProjectBuildFileParser} which is stateful and not multithreaded, but Graph Engine
 * transformations require parsers to be concurrent.
 *
 * <p>Another option would be to modify existing {@link PythonDslProjectBuildFileParser} to be
 * concurrent. However the legacy {@link Parser} pipelines use {@link
 * com.facebook.buck.util.concurrent.ResourcePool}s to shard parser instances, though in fact it is
 * not needed for {@link com.facebook.buck.skylark.parser.SkylarkProjectBuildFileParser} which is
 * concurrent. So, it will require to refactor existing parser pipelines to get rid of {@link
 * com.facebook.buck.util.concurrent.ResourcePool} which is a bigger change, so it probably should
 * be done once parsing is fully switched to Graph Engine.
 */
public class ConcurrentProjectBuildFileParser implements ProjectBuildFileParser {

  private final Supplier<ProjectBuildFileParser> projectBuildFileParserFactory;
  private final Queue<ProjectBuildFileParser> parsers = new ConcurrentLinkedQueue<>();

  /**
   * Create new instance of {@link ConcurrentProjectBuildFileParser}
   *
   * @param projectBuildFileParserFactory Factory that will be used for creating new instances of
   *     {@link ProjectBuildFileParser} on demand
   */
  public ConcurrentProjectBuildFileParser(
      Supplier<ProjectBuildFileParser> projectBuildFileParserFactory) {
    this.projectBuildFileParserFactory = projectBuildFileParserFactory;
  }

  /**
   * Convenience wrapper to return parser back to queue using try-with-resources for further reuse
   */
  private CloseableWrapper<ProjectBuildFileParser> getWrapper() {
    @Nullable ProjectBuildFileParser next = parsers.poll();
    if (next == null) {
      next = projectBuildFileParserFactory.get();
    }
    return CloseableWrapper.of(next, parser -> parsers.add(parser));
  }

  @Override
  public BuildFileManifest getBuildFileManifest(Path buildFile)
      throws BuildFileParseException, InterruptedException, IOException {
    try (CloseableWrapper<ProjectBuildFileParser> wrapper = getWrapper()) {
      return wrapper.get().getBuildFileManifest(buildFile);
    }
  }

  @Override
  public void reportProfile() throws IOException {
    // not synchronized, should only be used once parsing is done
    for (ProjectBuildFileParser parser : parsers) {
      parser.reportProfile();
    }
  }

  @Override
  public ImmutableSortedSet<String> getIncludedFiles(Path buildFile)
      throws BuildFileParseException, InterruptedException, IOException {
    try (CloseableWrapper<ProjectBuildFileParser> wrapper = getWrapper()) {
      return wrapper.get().getIncludedFiles(buildFile);
    }
  }

  @Override
  public boolean globResultsMatchCurrentState(
      Path buildFile, ImmutableList<GlobSpecWithResult> existingGlobsWithResults)
      throws IOException, InterruptedException {
    try (CloseableWrapper<ProjectBuildFileParser> wrapper = getWrapper()) {
      return wrapper.get().globResultsMatchCurrentState(buildFile, existingGlobsWithResults);
    }
  }

  @Override
  public void close() throws BuildFileParseException, InterruptedException, IOException {
    // not synchronized, should only be used once parsing is done
    for (ProjectBuildFileParser parser : parsers) {
      parser.close();
    }
    parsers.clear();
  }
}
