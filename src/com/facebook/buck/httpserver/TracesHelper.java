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

package com.facebook.buck.httpserver;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;

/**
 * Utility to help with reading data from build trace files.
 */
public class TracesHelper {

  private final ProjectFilesystem projectFilesystem;

  TracesHelper(ProjectFilesystem projectFilesystem) {
    this.projectFilesystem = projectFilesystem;
  }

  static class TraceAttributes {
    private static final ThreadLocal<DateFormat> DATE_FORMAT = new ThreadLocal<DateFormat>() {
      @Override
      protected DateFormat initialValue() {
        return new SimpleDateFormat("EEE, MMM d h:mm a");
      }
    };

    private final Optional<String> command;
    private final long lastModifiedTime;

    TraceAttributes(Optional<String> command, long lastModifiedTime) {
      this.command = command;
      this.lastModifiedTime = lastModifiedTime;
    }

    public Optional<String> getCommand() {
      return command;
    }

    public long getLastModifiedTime() {
      return lastModifiedTime;
    }

    public String getFormattedDateTime() {
      if (lastModifiedTime != 0) {
        return DATE_FORMAT.get().format(new Date(lastModifiedTime));
      } else {
        return "";
      }
    }
  }

  Iterable<InputStream> getInputsForTraces(String id) throws IOException {
    ImmutableList.Builder<InputStream> tracesBuilder = ImmutableList.builder();
    for (Path p : getPathsToTraces(id)) {
      tracesBuilder.add(projectFilesystem.getInputStreamForRelativePath(p));
    }
    return tracesBuilder.build();
  }

  TraceAttributes getTraceAttributesFor(String id) throws IOException {
    for (Path p : getPathsToTraces(id)) {
      if (isTraceForBuild(p, id)) {
        return getTraceAttributesFor(p);
      }
    }
    throw new HumanReadableException("Could not find a build trace with id %s.", id);
  }

  /**
   * Parses a trace file and returns the command that the user executed to create the trace.
   * <p>
   * This method tries to be reasonably tolerant of changes to the .trace file schema, returning
   * {@link Optional#absent()} if it does not find the fields in the JSON that it expects.
   */
  TraceAttributes getTraceAttributesFor(Path pathToTrace) throws IOException {
    long lastModifiedTime = projectFilesystem.getLastModifiedTime(pathToTrace);
    Optional<String> command = parseCommandFrom(pathToTrace);
    return new TraceAttributes(command, lastModifiedTime);
  }

  private Optional<String> parseCommandFrom(Path pathToTrace) {
    try (
        InputStream input = projectFilesystem.newFileInputStream(pathToTrace);
        JsonReader jsonReader = new JsonReader(new InputStreamReader(input))) {
      jsonReader.beginArray();
      Gson gson = new Gson();
      JsonObject json = gson.fromJson(jsonReader, JsonObject.class);
      JsonElement nameEl = json.get("name");
      if (nameEl == null || !nameEl.isJsonPrimitive()) {
        return Optional.absent();
      }

      JsonElement argsEl = json.get("args");
      if (argsEl == null ||
          !argsEl.isJsonObject() ||
          argsEl.getAsJsonObject().get("command_args") == null ||
          !argsEl.getAsJsonObject().get("command_args").isJsonPrimitive()) {
        return Optional.absent();
      }

      String name = nameEl.getAsString();
      String commandArgs = argsEl.getAsJsonObject().get("command_args").getAsString();
      String command = "buck " + name + " " + commandArgs;

      return Optional.of(command);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private boolean isTraceForBuild(Path path, String id) {
    String testPrefix = "build.";
    String testSuffix = "." + id + ".trace";
    String name = path.getFileName().toString();
    return name.startsWith(testPrefix) && name.endsWith(testSuffix);
  }

  Collection<Path> listTraceFilesByLastModified() throws IOException {
    return projectFilesystem.getSortedMatchingDirectoryContents(
        BuckConstant.BUCK_TRACE_DIR,
        "build.*.trace");
  }

  /**
   * Returns a collection of paths containing traces for the specified build ID.
   *
   * A given build might have more than one trace file (for example,
   * the buck.py launcher has its own trace file).
   */
  private Collection<Path> getPathsToTraces(String id) throws IOException {
    Preconditions.checkArgument(TracesHandlerDelegate.TRACE_ID_PATTERN.matcher(id).matches());

    Collection<Path> traces = projectFilesystem.getSortedMatchingDirectoryContents(
        BuckConstant.BUCK_TRACE_DIR,
        "*" + id + "*.trace");

    if (traces.isEmpty()) {
      throw new HumanReadableException("Could not find a build trace with id %s.", id);
    } else {
      return traces;
    }
  }
}
