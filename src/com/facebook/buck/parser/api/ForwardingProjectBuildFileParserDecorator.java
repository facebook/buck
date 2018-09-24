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

package com.facebook.buck.parser.api;

import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.skylark.io.GlobSpecWithResult;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Path;

/**
 * A convenience decorator for {@link ProjectBuildFileParser} that forwards all method invocation to
 * the delegate.
 *
 * <p>This decorator makes it easy to write decorators that are supposed to modify the behavior of a
 * subset of {@link ProjectBuildFileParser} methods.
 */
public class ForwardingProjectBuildFileParserDecorator implements ProjectBuildFileParser {

  protected final ProjectBuildFileParser delegate;

  protected ForwardingProjectBuildFileParserDecorator(ProjectBuildFileParser delegate) {
    this.delegate = delegate;
  }

  @Override
  public BuildFileManifest getBuildFileManifest(Path buildFile)
      throws BuildFileParseException, InterruptedException, IOException {
    return delegate.getBuildFileManifest(buildFile);
  }

  @Override
  public void reportProfile() throws IOException {
    delegate.reportProfile();
  }

  @Override
  public ImmutableList<String> getIncludedFiles(Path buildFile)
      throws BuildFileParseException, InterruptedException, IOException {
    return delegate.getIncludedFiles(buildFile);
  }

  @Override
  public boolean globResultsMatchCurrentState(
      Path buildFile, ImmutableList<GlobSpecWithResult> existingGlobsWithResults)
      throws IOException, InterruptedException {
    return delegate.globResultsMatchCurrentState(buildFile, existingGlobsWithResults);
  }

  @Override
  public void close() throws BuildFileParseException, InterruptedException, IOException {
    delegate.close();
  }
}
