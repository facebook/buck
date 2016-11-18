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

import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.step.ExecutionContext;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Specialized parser for preprocessed output produced by either
 * <code>g++ -E -dI file.cpp</code> or <code>clang++ -cc1 -E -dI file.cpp</code>.
 *
 * This preprocessed output contains inclusion directives (because of `-dI`) as written in the
 * source, followed by the a line marker referencing the full path to the included file itself.
 *
 * These are what we use to determine how an include like `#include "foo.h"` or `#include <bar.h>`
 * was resolved into `/absolute/path/foo.h` or `/some/other/path/bar.h`: that there was an
 * `-I /absolute/path` or `-isystem /some/other/path`.
 */
public class IncludeLog {

  /** id => IncludeLogEntry with that id */
  private final SortedMap<Integer, IncludeLogEntry> entryTable;

  @VisibleForTesting
  protected IncludeLog(ImmutableList<IncludeLogEntry> entries) {
    this(buildEntryTable(entries));
  }

  protected IncludeLog(SortedMap<Integer, IncludeLogEntry> entryTable) {
    this.entryTable = entryTable;
  }

  private static SortedMap<Integer, IncludeLogEntry> buildEntryTable(
      ImmutableList<IncludeLogEntry> entries) {
    SortedMap<Integer, IncludeLogEntry> ret = new TreeMap<>();
    for (IncludeLogEntry e : entries) {
      ret.put(e.id(), e);
    }
    return ret;
  }

  public ImmutableList<IncludeLogEntry> getEntries() {
    return ImmutableList.copyOf(entryTable.values());
  }

  protected IncludeLogEntry getEntryByID(int id) {
    IncludeLogEntry ret = this.entryTable.get(id);
    if (ret == null) {
      throw new IllegalArgumentException("no entry with id " + id);
    }
    return ret;
  }

  public void write(Path path) throws IOException {
    Path tmpPath = Paths.get(path.toString() + ".tmp");
    try (OutputStream outputStream = new FileOutputStream(tmpPath.toFile());
         Writer writer = new OutputStreamWriter(outputStream)) {
      write(writer);
    }
    Files.move(tmpPath, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
  }

  public void write(Writer writer) throws IOException {
    try (PrintWriter out = new PrintWriter(writer)) {
      for (IncludeLogEntry entry : entryTable.values()) {
        entry.write(out);
      }
    }
  }

  public static IncludeLog read(Path path) throws IOException {
    try (InputStream inputStream = new FileInputStream(path.toFile());
         Reader reader = new InputStreamReader(inputStream)) {
      return read(reader);
    }
  }

  public static IncludeLog read(Reader genericReader) throws IOException {
    try (BufferedReader reader = new BufferedReader(genericReader)) {
      SortedMap<Integer, IncludeLogEntry> entryTable = new TreeMap<>();
      String line;
      while ((line = reader.readLine()) != null) {
        IncludeLogEntry entry = IncludeLogEntry.read(line, entryTable);
        entryTable.put(entry.id(), entry);
      }
      return new IncludeLog(entryTable);
    }
  }

  public static IncludeLog parsePreprocessedCxx(Path path) throws IOException {
    try (InputStream input = new FileInputStream(path.toFile());
         Reader reader = new InputStreamReader(input)) {
      return parsePreprocessedCxx(reader);
    }
  }

  public static IncludeLog parsePreprocessedCxx(Reader reader) throws IOException {
    return new IncludeLog(new IncludeLogCParser().parse(reader).getEntryTable());
  }

  public static int parseAndWriteBuckCompatibleIncludeLogfile(
      ExecutionContext context,
      Path sourceIIFile,
      Path destIncludeLog) throws IOException {
    try {
      parsePreprocessedCxx(sourceIIFile).write(destIncludeLog);
    } catch (Exception e) {
      context.getBuckEventBus().post(
          ConsoleEvent.create(
              Level.SEVERE,
              "error at preprocessed input line: %s",
              e.getMessage()));
      return 1;
    }
    return 0;
  }

}
