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

import static org.junit.Assert.assertEquals;
import com.google.common.collect.ImmutableList;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import org.junit.Test;

public class IncludeLogTest {
  private static IncludeLogEntry angled(
      int id, String includerPath, String param, String includeePath) {
    return IncludeLogEntry.of(
        id,
        Optional.empty(),
        Paths.get(includerPath),
        IncludeLogEntry.InclusionKind.INCLUDE,
        IncludeLogEntry.QuoteKind.ANGLE,
        param,
        Paths.get(includeePath));
  }

  private static IncludeLogEntry angled(
      String includerPath, String param, String includeePath) {
    return angled(0, includerPath, param, includeePath);
  }

  private static IncludeLogEntry quoted(
      int id, String includerPath, String param, String includeePath) {
    return IncludeLogEntry.of(
        id,
        Optional.empty(),
        Paths.get(includerPath),
        IncludeLogEntry.InclusionKind.INCLUDE,
        IncludeLogEntry.QuoteKind.QUOTE,
        param,
        Paths.get(includeePath));
  }

  private static IncludeLogEntry quoted(
      String includerPath, String param, String includeePath) {
    return quoted(0, includerPath, param, includeePath);
  }

  @Test
  public void parseWithQuotes() throws IOException {
    IncludeLog includeLog = IncludeLog.parsePreprocessedCxx(new StringReader(
        "# 42 \"test.cpp\"\n" +
        "#include \"foo.h\"\n" +
        "# 1 \"/absolute/path/to/foo.h\" 1\n" +
        "# 43 \"test.cpp\" 2\n"
    ));
    assertEquals(
        ImmutableList.<IncludeLogEntry>of(
            quoted("test.cpp", "foo.h", "/absolute/path/to/foo.h")),
        includeLog.getEntries());
  }

  @Test
  public void parseWithAngles() throws IOException {
    IncludeLog includeLog = IncludeLog.parsePreprocessedCxx(new StringReader(
        "# 42 \"test.cpp\"\n" +
        "#include <foo.h>\n" +
        "# 42 \"/absolute/path/to/foo.h\" 1\n" +
        "# 43 \"test.cpp\" 2\n"
    ));
    assertEquals(
        ImmutableList.<IncludeLogEntry>of(
            angled("test.cpp", "foo.h", "/absolute/path/to/foo.h")),
        includeLog.getEntries());
  }

  @Test
  public void parseWithoutIncluderLineMarker() throws IOException {
    IncludeLog includeLog = IncludeLog.parsePreprocessedCxx(new StringReader(
        "# 42 \"test.cpp\"\n" +
        "#include <foo.h>\n" +
        "# 1 \"path/to/foo.h\" 1\n" +
        "# 43 \"test.cpp\" 2\n" +
        "endoftestfile;"
    ));
    assertEquals(
        ImmutableList.<IncludeLogEntry>of(
            angled("test.cpp", "foo.h", "path/to/foo.h")),
        includeLog.getEntries());
  }

  @Test
  public void parseWithIncluderLineMarker() throws IOException {
    IncludeLog includeLog = IncludeLog.parsePreprocessedCxx(new StringReader(
        "# 42 \"test.cpp\"\n" +
        "#include <foo.h>\n" +
        "# 4 \"test.cpp\" 99\n" +
        "# 1 \"path/to/foo.h\" 1\n" +
        "# 1 \"test.cpp\" 2\n" +
        "endoftestfile;"
    ));
    assertEquals(
        ImmutableList.<IncludeLogEntry>of(
            angled("test.cpp", "foo.h", "path/to/foo.h")),
        includeLog.getEntries());
  }

  @Test
  public void parseManyIncludes() throws IOException {
    IncludeLog includeLog = IncludeLog.parsePreprocessedCxx(new StringReader(
        "# 42 \"test.cpp\"\n" +
        "#include <foo.h>\n" +
        "# 1 \"path/to/foo.h\" 1 3\n" +
        "# 2 \"test.cpp\" 2\n" +
        "#include <bar.h>\n" +
        "# 3 \"test.cpp\"\n" +
        "# 1 \"path/to/bar.h\" 1 3\n" +
        "# 4 \"test.cpp\" 2\n"
    ));
    assertEquals(
        ImmutableList.<IncludeLogEntry>of(
            angled("test.cpp", "foo.h", "path/to/foo.h"),
            angled("test.cpp", "bar.h", "path/to/bar.h")),
        includeLog.getEntries());
  }

  @Test
  public void parseNoIncludes() throws IOException {
    IncludeLog includeLog = IncludeLog.parsePreprocessedCxx(new StringReader(
        "# 42 \"test.cpp\"\n" +
        "# 43 \"test.cpp\"\n" +
        "# 44 \"test.cpp\"\n" +
        " ... yada yada yada ...\n" +
        "# 45 \"test.cpp\"\n"
    ));
    assertEquals(
        ImmutableList.<IncludeLogEntry>of(),
        includeLog.getEntries());
  }

  @Test
  public void parseWithOtherJunkLines() throws IOException {
    IncludeLog includeLog = IncludeLog.parsePreprocessedCxx(new StringReader(
        "# 42 \"./test.cpp\"\n" +
        "#include <foo.h>\n" +
        "a\n" +
        "# 4 \"./test.cpp\"\n" +
        "b\r\n" +
        "\r\n" +
        "# 1 \"path/to/foo.h\" 1 9\n" +
        "# 2 \"./test.cpp\" 2 9\n"
    ));
    assertEquals(
        ImmutableList.<IncludeLogEntry>of(
            angled("test.cpp", "foo.h", "path/to/foo.h")),
        includeLog.getEntries());
  }

  @Test
  public void parseWithWeirdChars() throws IOException {
    IncludeLog includeLog = IncludeLog.parsePreprocessedCxx(new StringReader(
        "# 42 \"test.cpp\"\n" +
        "#include <fo\\o.h>\n" +
        "# 1 \"dir/with/\"Quote/\\\\Backslash/fo\\\\o.h\" 1\n" +
        "# 43 \"test.cpp\" 2\n" +
        "#include \"ba>r.h\"\n" +
        "# 1 \"dir/with/\"Quote/\\\\Backslash/ba>r.h\" 1\n" +
        "# 44 \"test.cpp\" 2\n"
    ));
    assertEquals(
        ImmutableList.<IncludeLogEntry>of(
            angled("test.cpp", "fo\\o.h", "dir/with/_Quote/\\Backslash/fo\\o.h"),
            quoted("test.cpp", "ba>r.h", "dir/with/_Quote/\\Backslash/ba_r.h")),
        includeLog.getEntries());
  }

  /** where test data are kept; subdirectory of this class file's dir. */
  private static final String TEST_DATA_DIR = "test/com/facebook/buck/cxx/testdata/include_log";

  @Test
  public void completeTest() throws Exception {
    try (InputStream fileInputStream = new FileInputStream(
            TEST_DATA_DIR + "/preproc-output-with-includes-gcc.ii.gz");
         GZIPInputStream gzInputStream = new GZIPInputStream(fileInputStream);
         Reader reader = new InputStreamReader(gzInputStream)) {
      IncludeLog.parsePreprocessedCxx(reader);
    }

    try (InputStream fileInputStream = new FileInputStream(
            TEST_DATA_DIR + "/preproc-output-with-includes-clang.ii.gz");
         GZIPInputStream gzInputStream = new GZIPInputStream(fileInputStream);
         Reader reader = new InputStreamReader(gzInputStream)) {
      IncludeLog.parsePreprocessedCxx(reader);
    }
  }
}
