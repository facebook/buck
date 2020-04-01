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

package com.facebook.buck.core.starlark.testutil;

import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.syntax.FuncallExpression;
import com.google.devtools.build.lib.syntax.Parser;
import com.google.devtools.build.lib.syntax.ParserInputSource;
import com.google.devtools.build.lib.vfs.PathFragment;

public class TestStarlarkParser {

  private static class ThrowingEventHandler implements EventHandler {
    @Override
    public void handle(Event event) {
      throw new AssertionError("unexpected: " + event);
    }
  }

  public static FuncallExpression parseFuncall(String expr) {
    return (FuncallExpression)
        Parser.parseExpression(
            ParserInputSource.create(expr, PathFragment.EMPTY_FRAGMENT),
            new ThrowingEventHandler());
  }
}
