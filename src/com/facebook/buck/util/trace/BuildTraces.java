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

package com.facebook.buck.util.trace;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.trace.ChromeTraceParser.ChromeTraceEventMatcher;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Utility to help with reading data from build trace files. */
public class BuildTraces {

  /**
   * Regex pattern that can be used as a parameter to {@link Pattern#compile(String)} to match a
   * valid trace id.
   */
  private static final String TRACE_ID_PATTERN_TEXT = "([0-9a-zA-Z-]+)";

  public static final Pattern TRACE_ID_PATTERN = Pattern.compile(TRACE_ID_PATTERN_TEXT);

  private static final Logger logger = Logger.get(BuildTraces.class);

  private static final Pattern TRACES_FILE_PATTERN = Pattern.compile("build\\..*\\.trace$");

  private final ProjectFilesystem projectFilesystem;

  public BuildTraces(ProjectFilesystem projectFilesystem) {
    this.projectFilesystem = projectFilesystem;
  }

  public static class TraceAttributes {
    private static final ThreadLocal<DateFormat> DATE_FORMAT =
        new ThreadLocal<DateFormat>() {
          @Override
          protected DateFormat initialValue() {
            return new SimpleDateFormat("EEE, MMM d h:mm a");
          }
        };

    private final Optional<String> command;
    private final FileTime lastModifiedTime;

    public TraceAttributes(Optional<String> command, FileTime lastModifiedTime) {
      this.command = command;
      this.lastModifiedTime = lastModifiedTime;
    }

    public Optional<String> getCommand() {
      return command;
    }

    public FileTime getLastModifiedTime() {
      return lastModifiedTime;
    }

    public String getFormattedDateTime() {
      if (lastModifiedTime.toMillis() != 0) {
        return DATE_FORMAT.get().format(Date.from(lastModifiedTime.toInstant()));
      } else {
        return "";
      }
    }
  }

  public Iterable<InputStream> getInputsForTraces(String id) throws IOException {
    ImmutableList.Builder<InputStream> tracesBuilder = ImmutableList.builder();
    for (Path p : getPathsToTraces(id)) {
      tracesBuilder.add(projectFilesystem.getInputStreamForRelativePath(p));
    }
    return tracesBuilder.build();
  }

  public TraceAttributes getTraceAttributesFor(String id) throws IOException {
    for (Path p : getPathsToTraces(id)) {
      if (isTraceForBuild(p, id)) {
        return getTraceAttributesFor(p);
      }
    }
    throw new HumanReadableException("Could not find a build trace with id %s.", id);
  }

  /**
   * Parses a trace file and returns the command that the user executed to create the trace.
   *
   * <p>This method tries to be reasonably tolerant of changes to the .trace file schema, returning
   * {@link Optional#empty()} if it does not find the fields in the JSON that it expects.
   */
  public TraceAttributes getTraceAttributesFor(Path pathToTrace) throws IOException {
    FileTime lastModifiedTime = projectFilesystem.getLastModifiedTime(pathToTrace);
    Optional<String> command = parseCommandFrom(pathToTrace);
    return new TraceAttributes(command, lastModifiedTime);
  }

  private Optional<String> parseCommandFrom(Path pathToTrace) {
    Set<ChromeTraceParser.ChromeTraceEventMatcher<?>> matchers =
        ImmutableSet.of(ChromeTraceParser.COMMAND);
    ChromeTraceParser parser = new ChromeTraceParser(projectFilesystem);

    Map<ChromeTraceEventMatcher<?>, Object> results;
    try {
      results = parser.parse(pathToTrace, matchers);
    } catch (IOException e) {
      logger.error(e);
      return Optional.empty();
    }

    return ChromeTraceParser.getResultForMatcher(ChromeTraceParser.COMMAND, results);
  }

  private boolean isTraceForBuild(Path path, String id) {
    String testPrefix = "build.";
    String testSuffix = "." + id + ".trace";
    String name = path.getFileName().toString();
    return name.startsWith(testPrefix) && name.endsWith(testSuffix);
  }

  /** The most recent trace (the one with the greatest last-modified time) will be listed first. */
  public List<Path> listTraceFilesByLastModified() throws IOException {
    List<Path> allTraces = new ArrayList<>();

    projectFilesystem.walkFileTree(
        projectFilesystem.getBuckPaths().getLogDir(),
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Matcher matcher = TRACES_FILE_PATTERN.matcher(file.getFileName().toString());
            if (matcher.matches()) {
              allTraces.add(file);
            }

            return super.visitFile(file, attrs);
          }
        });

    // Sort by:
    // 1. Reverse chronological order.
    // 2. Alphabetical order.
    Collections.sort(
        allTraces,
        (path1, path2) -> {
          int result = 0;
          FileTime lastModifiedTime1;
          FileTime lastModifiedTime2;
          try {
            lastModifiedTime1 = projectFilesystem.getLastModifiedTime(path1);
            lastModifiedTime2 = projectFilesystem.getLastModifiedTime(path2);
          } catch (IOException e) {
            throw new RuntimeException(e);
          }

          result = lastModifiedTime2.compareTo(lastModifiedTime1);
          if (result == 0) {
            return path2.toString().compareTo(path1.toString());
          } else {
            return result;
          }
        });

    return allTraces;
  }

  /**
   * Returns a collection of paths containing traces for the specified build ID.
   *
   * <p>A given build might have more than one trace file (for example, the buck.py launcher has its
   * own trace file).
   */
  private Collection<Path> getPathsToTraces(String id) throws IOException {
    Preconditions.checkArgument(TRACE_ID_PATTERN.matcher(id).matches());
    List<Path> traces =
        listTraceFilesByLastModified().stream()
            .filter(input -> input.getFileName().toString().contains(id))
            .collect(Collectors.toList());

    if (traces.isEmpty()) {
      throw new HumanReadableException("Could not find a build trace with id %s.", id);
    } else {
      return traces;
    }
  }
}
