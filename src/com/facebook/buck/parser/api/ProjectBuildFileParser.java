/*
 * Copyright 2017-present Facebook, Inc.
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
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;

/** Parses buck build files (usually BUCK files) and retrieve rule information from them. */
public interface ProjectBuildFileParser extends AutoCloseable {

  /**
   * Collect all rules from a particular build file, along with meta rules about the rules, for
   * example which build files the rules depend on.
   *
   * @param buildFile should be an absolute path to a build file. Must have rootPath as its prefix.
   */
  BuildFileManifest getBuildFileManifest(Path buildFile)
      throws BuildFileParseException, InterruptedException, IOException;

  /** Reports profile information captured while parsing build files. */
  void reportProfile() throws IOException;

  /**
   * Collects the loaded build file and extensions when parsing the {@code buildFile} build spec.
   *
   * @param buildFile should be an absolute path to a build file. Must have rootPath as its prefix.
   */
  ImmutableSortedSet<String> getIncludedFiles(Path buildFile)
      throws BuildFileParseException, InterruptedException, IOException;

  /**
   * Checks if existing {@code GlobSpec}s with results are the same as current state in the file
   * system.
   *
   * @param existingGlobsWithResults the existing (deserialized) {@code GlobSpecWithResult} to check
   *     the file system state against.
   * @param buildFile the buildFile location to be used by the Globber.
   * @return {@code true} if glob expansion produces results matching previously recorded ones,
   *     {@code false} otherwise.
   */
  boolean globResultsMatchCurrentState(
      Path buildFile, ImmutableList<GlobSpecWithResult> existingGlobsWithResults)
      throws IOException, InterruptedException;

  @Override
  void close() throws BuildFileParseException, InterruptedException, IOException;
}
