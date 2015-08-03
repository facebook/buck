/*
 * Copyright 2015-present Facebook, Inc.
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

import static java.nio.charset.Charset.defaultCharset;

import com.facebook.buck.util.Escaper;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Joiner;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@SuppressFieldNotInitialized
public class CxxCompilationDatabaseEntry {

  public String directory;
  public String file;
  public ImmutableList<String> args;
  public String command;

  public CxxCompilationDatabaseEntry(
      String directory,
      String file,
      ImmutableList<String> args) {
    this.directory = directory;
    this.file = file;
    this.args = args;
    this.command = Joiner.on(' ').join(
        Iterables.transform(
            args,
            Escaper.SHELL_ESCAPER));
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof CxxCompilationDatabaseEntry)) {
      return false;
    }

    CxxCompilationDatabaseEntry that = (CxxCompilationDatabaseEntry) obj;
    return Objects.equal(this.directory, that.directory) &&
        Objects.equal(this.file, that.file) &&
        Objects.equal(this.args, that.args) &&
        Objects.equal(this.command, that.command);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(directory, file, args, command);
  }

  // Useful if CompilationDatabaseTest fails when comparing JsonSerializableDatabaseEntry objects.
  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("directory", directory)
        .add("file", file)
        .add("args", args)
        .add("command", command)
        .toString();
  }

  private static class ArgsJsonDeserializer implements JsonDeserializer<ImmutableList<String>> {
    @Override
    public ImmutableList<String> deserialize(
        JsonElement json,
        Type type,
        JsonDeserializationContext context) throws JsonParseException {
      Type listType = new TypeToken<Collection<String>>(){}.getType();
      List<String> list = context.deserialize(json, listType);
      return ImmutableList.copyOf(list);
    }
  }

  public static Map<String, CxxCompilationDatabaseEntry> parseCompilationDatabaseJsonFile(
      Path compilationDatabase) throws IOException {
    Gson gson = new GsonBuilder()
        .registerTypeAdapter(ImmutableList.class, new ArgsJsonDeserializer())
        .create();
    try (Reader fileReader = Files.newBufferedReader(compilationDatabase, defaultCharset())) {
      List<CxxCompilationDatabaseEntry> entries = gson
          .fromJson(
              fileReader, new TypeToken<List<CxxCompilationDatabaseEntry>>() {
              }.getType());
      Map<String, CxxCompilationDatabaseEntry> fileToEntry = Maps.newHashMap();
      for (CxxCompilationDatabaseEntry entry : entries) {
        fileToEntry.put(entry.file, entry);
      }
      return fileToEntry;
    }
  }
}
