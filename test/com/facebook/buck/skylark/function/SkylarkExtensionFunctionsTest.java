/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.skylark.function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.events.PrintingEventHandler;
import com.google.devtools.build.lib.syntax.BuildFileAST;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.Mutability;
import com.google.devtools.build.lib.syntax.ParserInputSource;
import com.google.devtools.build.lib.syntax.Runtime;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.EnumSet;
import org.junit.Before;
import org.junit.Test;

public class SkylarkExtensionFunctionsTest {

  private PrintingEventHandler eventHandler;

  @Before
  public void setUp() throws InterruptedException {
    eventHandler = new PrintingEventHandler(EnumSet.allOf(EventKind.class));
  }

  @Test
  public void canAccessStructFields() throws Exception {
    Environment env = evaluate("s = struct(x=2,y=3); sum = s.x + s.y");
    assertEquals(5, env.lookup("sum"));
  }

  @Test
  public void canConvertStructToJson() throws Exception {
    Environment env = evaluate("s = struct(x=2,y=3); json = s.to_json()");
    assertEquals("{\"x\":2,\"y\":3}", env.lookup("json"));
  }

  /** Evaluates Skylark content and returns an environment produced during execution. */
  private Environment evaluate(String content) throws InterruptedException {
    BuildFileAST buildFileAST = parseBuildFile(content);
    assertFalse(buildFileAST.containsErrors());
    try (Mutability mutability = Mutability.create("test")) {
      Environment env = Environment.builder(mutability).useDefaultSemantics().build();
      Runtime.setupModuleGlobals(env, SkylarkExtensionFunctions.class);
      boolean result = buildFileAST.exec(env, eventHandler);
      assertTrue(result);
      return env;
    }
  }

  private BuildFileAST parseBuildFile(String content) {
    return BuildFileAST.parseSkylarkFile(
        ParserInputSource.create(content, PathFragment.create("BUCK")), eventHandler);
  }
}
