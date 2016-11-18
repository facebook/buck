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
import static org.junit.Assert.assertNotEquals;
import com.google.common.base.Preconditions;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import org.junit.Test;

public class IncludeLogEntryTest {
  private static IncludeLogEntry quoted(
      int id, String includer, IncludeLogEntry.InclusionKind kind, String param, String includee) {
    return IncludeLogEntry.of(
        id,
        Optional.empty(),
        Paths.get(includer),
        kind,
        IncludeLogEntry.QuoteKind.QUOTE,
        param,
        Paths.get(includee));
  }

  private static IncludeLogEntry angled(
      int id, String includer, IncludeLogEntry.InclusionKind kind, String param, String includee) {
    return IncludeLogEntry.of(
        id,
        Optional.empty(),
        Paths.get(includer),
        kind,
        IncludeLogEntry.QuoteKind.ANGLE,
        param,
        Paths.get(includee));
  }

  private static IncludeLogEntry quoted(int id, String includer, String param, String includee) {
    return quoted(id, includer, IncludeLogEntry.InclusionKind.INCLUDE, param, includee);
  }

  private static IncludeLogEntry angled(int id, String includer, String param, String includee) {
    return angled(id, includer, IncludeLogEntry.InclusionKind.INCLUDE, param, includee);
  }

  @Test
  public void equalityTests() {
    IncludeLogEntry foo1 = quoted(1, "./foo.cpp", "bar.h", "./bar.h");
    IncludeLogEntry foo2 = quoted(2, "./foo.cpp", "bar.h", "./bar.h");
    assertEquals(foo1, foo2);
    assertEquals(foo2, foo1);
    assertEquals(foo2.hashCode(), foo1.hashCode());

    IncludeLogEntry xyz1 = quoted(3, "ZZZ./foo.cpp", "bar.h", "./bar.h");
    IncludeLogEntry xyz2 = quoted(4, "./foo.cpp", "ZZZbar.h", "./ZZZbar.h");
    IncludeLogEntry xyz3 = quoted(5, "./foo.cpp", "bar.h", "./ZZZ/bar.h");
    IncludeLogEntry xyz4 = angled(6, "./foo.cpp", "bar.h", "./bar.h");
    assertNotEquals(foo1, xyz1);
    assertNotEquals(xyz1, foo1);
    assertNotEquals(foo1, xyz2);
    assertNotEquals(xyz2, foo1);
    assertNotEquals(foo1, xyz3);
    assertNotEquals(xyz3, foo1);
    assertNotEquals(foo1, xyz4);
    assertNotEquals(xyz4, foo1);

    IncludeLogEntry foo3 = quoted(7, "./foo.cpp", "bar.h", "././bar.h"); // extra "./" in last part
    assertEquals(foo1, foo3);
  }

  @Test
  public void serializationTests() throws Exception {
    try (StringWriter sw = new StringWriter();
         PrintWriter pw = new PrintWriter(sw)) {

      final IncludeLogEntry foo = quoted(1, "./foo.cpp", "bar.h", "./bar.h");
      foo.write(pw);
      pw.flush();
      sw.flush();

      final String serialized = sw.toString();
      assertEquals('\n', serialized.charAt(serialized.length() - 1));
      String[] parts = serialized.trim().split("\t");
      assertEquals(7, parts.length);

      Integer.parseInt(parts[0]);  // doesn't throw?
      assertEquals(-1, Integer.parseInt(parts[1]));
      assertEquals("foo.cpp", parts[2]);
      assertEquals(
          IncludeLogEntry.InclusionKind.INCLUDE, IncludeLogEntry.InclusionKind.valueOf(parts[3]));
      assertEquals("QUOTE", parts[4]);
      assertEquals("bar.h", parts[5]);
      assertEquals("bar.h", parts[6]);

      SortedMap<Integer, IncludeLogEntry> entryTable = new TreeMap<>();

      IncludeLogEntry bar = IncludeLogEntry.read(serialized, entryTable);
      entryTable.put(bar.id(), bar);

      assertEquals(foo, bar);

      final int bazID = bar.id() + 1; // has to be higher than its parent's ID
      IncludeLogEntry baz = IncludeLogEntry.read(
          bazID + "\t" + bar.id() + "\t" + "bar.h\tINCLUDE\tQUOTE\tbaz.h\tbaz.h\n", entryTable);
      entryTable.put(baz.id(), bar);

      assertEquals(bazID, baz.id());
      assertEquals(bar, Preconditions.checkNotNull(baz.getParent().orElse(null)));
      assertEquals("bar.h", baz.getCurrentFile().toString());
      assertEquals(IncludeLogEntry.InclusionKind.INCLUDE, baz.getInclusionKind());
      assertEquals(IncludeLogEntry.QuoteKind.QUOTE, baz.getQuoteKind());
      assertEquals("baz.h", baz.getParameter());
      assertEquals("baz.h", baz.getNextFile().toString());
    }
  }
}
