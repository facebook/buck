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

import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.type.TypeReference;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CxxCompilationDatabaseUtils {

  private CxxCompilationDatabaseUtils() {}

  public static Map<String, CxxCompilationDatabaseEntry> parseCompilationDatabaseJsonFile(
      Path compilationDatabase) throws IOException {
    try (Reader fileReader = Files.newBufferedReader(compilationDatabase, defaultCharset())) {
      List<CxxCompilationDatabaseEntry> entries =
          ObjectMappers.READER
              .forType(new TypeReference<CxxCompilationDatabaseEntry>() {})
              .<CxxCompilationDatabaseEntry>readValues(fileReader)
              .readAll();
      Map<String, CxxCompilationDatabaseEntry> fileToEntry = new HashMap<>();
      for (CxxCompilationDatabaseEntry entry : entries) {
        fileToEntry.put(entry.getFile(), entry);
      }
      return fileToEntry;
    }
  }
}
