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
package com.facebook.buck.cxx;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.base.Preconditions;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Stack;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Produces the error message when an untracked header is detected, showing an include chain. Parses
 * the output as emitted by {@code cl.exe /showIncludes}.
 *
 * <p>When preprocessing/compiling, we may encounter some headers that are missing from BUCK file.
 * If the file is not whitelisted this class generates the message for this error. It takes
 * advantage of the tree structure we have from the depfile to provide information about where does
 * the header is being included. This rich depfile is only available for cl.exe compiler, which is
 * created in {@Link CxxPreprocessAndCompileStep}.
 */
class UntrackedHeaderReporterWithShowIncludes implements UntrackedHeaderReporter {
  private static String CHAIN_SEPARATOR = ", which is included by: " + System.lineSeparator();
  private final Path inputPath;
  private final ProjectFilesystem filesystem;
  private final HeaderPathNormalizer headerPathNormalizer;
  private final Path sourceDepFile;
  // We parse the dependency tree by saving the parents for each header.
  // We load the tree lazily on request.
  @Nullable private ListMultimap<Path, Path> treeParents;

  public UntrackedHeaderReporterWithShowIncludes(
      ProjectFilesystem filesystem,
      HeaderPathNormalizer headerPathNormalizer,
      Path sourceDepFile,
      Path inputPath) {
    this.filesystem = filesystem;
    this.inputPath = inputPath;
    this.sourceDepFile = sourceDepFile;
    this.headerPathNormalizer = headerPathNormalizer;
    this.treeParents = null;
  }

  private List<Path> getPathToUntrackedHeader(Path header) throws IOException {
    // An intermediate depfile in `show_include` mode contains a source file + used headers
    // (see CxxPreprocessAndCompileStep for details).
    // So, we "strip" the the source file first.
    List<String> srcAndIncludes = filesystem.readLines(sourceDepFile);
    List<String> includes = srcAndIncludes.subList(1, srcAndIncludes.size());
    return getPathToUntrackedHeader(includes, header);
  }

  /**
   * @return a list of headers that represents a chain of includes ending in a particular header.
   */
  private List<Path> getPathToUntrackedHeader(List<String> includeLines, Path header) {
    // We parse the tree structure linearly by maintaining a stack of the current active parents.
    Stack<Path> active_parents = new Stack<Path>();
    for (String line : includeLines) {
      int currentDeepLevel = countCharAtTheBeginning(line, ' ') - 1;
      Preconditions.checkState(
          currentDeepLevel <= active_parents.size(),
          "Error parsing dependency file for %s",
          prettyPrintFileName(inputPath, true));
      while (currentDeepLevel < active_parents.size()) {
        active_parents.pop();
      }
      Path currentHeader = filesystem.resolve(line.trim()).normalize();
      currentHeader =
          headerPathNormalizer
              .getAbsolutePathForUnnormalizedPath(currentHeader)
              .orElse(currentHeader);
      active_parents.push(currentHeader);
      if (currentHeader.equals(header)) {
        return Lists.reverse(active_parents);
      }
    }
    return Collections.singletonList(header);
  }

  @Override
  public boolean isDetailed() {
    return true;
  }

  @Override
  public String getErrorReport(Path header) throws IOException {
    Path absolutePath =
        headerPathNormalizer.getAbsolutePathForUnnormalizedPath(header).orElse(header);
    List<Path> chain = getPathToUntrackedHeader(absolutePath);
    String errorMessage =
        String.format(
            "%s: included an untracked header: %n%s",
            prettyPrintFileName(inputPath, false), prettyPrintChain(chain));
    return errorMessage;
  }

  private String prettyPrintFileName(Path fileName, boolean quote) {
    Optional<Path> repoRelativePath = filesystem.getPathRelativeToProjectRoot(fileName);
    String prettyFilename = repoRelativePath.orElse(fileName).toString();
    if (quote) {
      prettyFilename = String.format("\"%s\"", prettyFilename);
    }
    return prettyFilename;
  }

  private String prettyPrintChain(List<Path> chain) {
    return chain.stream()
        .map((file) -> prettyPrintFileName(file, false))
        .collect(Collectors.joining(CHAIN_SEPARATOR));
  }

  private static int countCharAtTheBeginning(String str, char c) {
    int i = 0;
    while (i < str.length() && str.charAt(i) == c) {
      i++;
    }
    return i;
  }
}
