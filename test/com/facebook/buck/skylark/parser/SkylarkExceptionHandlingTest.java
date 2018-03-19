/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.skylark.parser;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventCollector;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.syntax.BaseFunction;
import com.google.devtools.build.lib.syntax.BuildFileAST;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.Mutability;
import com.google.devtools.build.lib.syntax.ParserInputSource;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.Iterator;
import org.junit.Test;

public class SkylarkExceptionHandlingTest {
  @Test
  public void locationIsIncludedWhenUnconfiguredFunctionIsReported() throws Exception {
    try (Mutability mutability = Mutability.create("BUCK")) {
      Environment env = Environment.builder(mutability).useDefaultSemantics().build();
      env.setup("foo", new BaseFunction("foo") {});

      EventCollector eventHandler = new EventCollector();
      BuildFileAST ast =
          BuildFileAST.parseBuildFile(
              ParserInputSource.create("foo()", PathFragment.create("BUCK")), eventHandler);
      assertThat(ast.containsErrors(), is(false));
      boolean success = ast.exec(env, eventHandler);
      assertThat(success, is(false));
      Iterator<Event> errors = eventHandler.filtered(EventKind.ERROR).iterator();
      assertThat(errors.hasNext(), is(true));
      Event event = errors.next();
      assertThat(errors.hasNext(), is(false));
      assertThat(event.getLocation().toString(), is("BUCK:1:1"));
      assertThat(event.getMessage(), is("Function foo was not configured"));
      assertThat(event.getKind(), is(EventKind.ERROR));
    }
  }
}
