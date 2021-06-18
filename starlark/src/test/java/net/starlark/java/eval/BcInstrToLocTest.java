/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.starlark.java.eval;

import static org.junit.Assert.*;

import com.google.common.collect.ImmutableList;
import net.starlark.java.syntax.FileLocations;
import net.starlark.java.syntax.Location;
import org.junit.Test;

public class BcInstrToLocTest {
  @Test
  public void test() {
    BcInstrToLoc.Builder builder = new BcInstrToLoc.Builder();
    builder.add(17, ImmutableList.of());
    builder.add(
        19,
        ImmutableList.of(
            new BcWriter.LocOffset("a", FileLocations.create("xx\nyy\n", "a.star"), 1),
            new BcWriter.LocOffset("b", FileLocations.create("zz\nyy\n", "b.star"), 4)));
    BcInstrToLoc instrToLoc = builder.build();

    assertEquals(ImmutableList.of(), instrToLoc.locationAt(17));
    assertEquals(
        ImmutableList.of(
            new StarlarkThread.CallStackEntry("a", new Location("a.star", 1, 2)),
            new StarlarkThread.CallStackEntry("b", new Location("b.star", 2, 2))),
        instrToLoc.locationAt(19));
  }
}
