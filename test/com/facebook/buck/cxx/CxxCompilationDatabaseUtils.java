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

import com.google.common.collect.ImmutableList;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CxxCompilationDatabaseUtils {

  private CxxCompilationDatabaseUtils() {}

  private static class ArgsJsonDeserializer implements JsonDeserializer<ImmutableList<String>> {
    @Override
    public ImmutableList<String> deserialize(
        JsonElement json, Type type, JsonDeserializationContext context) throws JsonParseException {
      Type listType = new TypeToken<Collection<String>>() {}.getType();
      List<String> list = context.deserialize(json, listType);
      return ImmutableList.copyOf(list);
    }
  }

  public static Map<String, CxxCompilationDatabaseEntry> parseCompilationDatabaseJsonFile(
      Path compilationDatabase) throws IOException {
    Gson gson =
        new GsonBuilder()
            .registerTypeAdapter(ImmutableList.class, new ArgsJsonDeserializer())
            .create();
    try (Reader fileReader = Files.newBufferedReader(compilationDatabase, defaultCharset())) {
      List<CxxCompilationDatabaseEntry> entries =
          gson.fromJson(
              fileReader, new TypeToken<List<CxxCompilationDatabaseEntry>>() {}.getType());
      Map<String, CxxCompilationDatabaseEntry> fileToEntry = new HashMap<>();
      for (CxxCompilationDatabaseEntry entry : entries) {
        fileToEntry.put(entry.getFile(), entry);
      }
      return fileToEntry;
    }
  }
}
