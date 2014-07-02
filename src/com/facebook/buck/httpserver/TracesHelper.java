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

import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.io.InputSupplier;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Utility to help with reading data from build trace files.
 */
public class TracesHelper {

  private final ProjectFilesystem projectFilesystem;

  TracesHelper(ProjectFilesystem projectFilesystem) {
    this.projectFilesystem = Preconditions.checkNotNull(projectFilesystem);
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
      this.command = Preconditions.checkNotNull(command);
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

  File[] listTraceFiles() {
    return projectFilesystem.listFiles(BuckConstant.BUCK_TRACE_DIR);
  }

  InputSupplier<? extends InputStream> getInputForTrace(String id) {
    Preconditions.checkNotNull(id);
    Path pathToTrace = getPathToTrace(id);
    return projectFilesystem.getInputSupplierForRelativePath(pathToTrace);
  }

  TraceAttributes getTraceAttributesFor(String id) {
    Path pathToTrace = getPathToTrace(id);
    return getTraceAttributesFor(projectFilesystem.getFileForRelativePath(pathToTrace));
  }

  /**
   * Parses a trace file and returns the command that the user executed to create the trace.
   * <p>
   * This method tries to be reasonably tolerant of changes to the .trace file schema, returning
   * {@link Optional#absent()} if it does not find the fields in the JSON that it expects.
   */
  TraceAttributes getTraceAttributesFor(File traceFile) {
    long lastModifiedTime = traceFile.lastModified();
    Path pathToTrace = BuckConstant.BUCK_TRACE_DIR.resolve(traceFile.getName());
    Optional<String> command = parseCommandFrom(pathToTrace);
    return new TraceAttributes(command, lastModifiedTime);
  }

  private Optional<String> parseCommandFrom(Path pathToTrace) {
    InputSupplier<? extends InputStream> inputSupplier = projectFilesystem
        .getInputSupplierForRelativePath(pathToTrace);
    try (JsonReader jsonReader = new JsonReader(new InputStreamReader(inputSupplier.getInput()))) {
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

  private static Path getPathToTrace(String id) {
    return BuckConstant.BUCK_TRACE_DIR.resolve(String.format("build.%s.trace", id));
  }
}
