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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

public class BcEvalTest {
  @Test
  public void stackTraceWithErrorInStarArgument() throws Exception {
    String program =
        "" //
            + "def f(): dict(**1)\n"
            + "f()";
    try {
      BcTestUtil.eval(program);
      fail("expecting exception");
    } catch (EvalException e) {
      assertEquals(
          "" //
              + "Traceback (most recent call last):\n"
              + "\tFile \"f.star\", line 2, column 2, in <toplevel>\n"
              + "\t\tf()\n"
              + "\tFile \"f.star\", line 1, column 15, in f\n"
              + "\t\tdef f(): dict(**1)\n"
              + "Error: argument after ** must be a dict, not int",
          e.getMessageWithStack(loc -> program.split("\n")[loc.line() - 1]));
    }
  }
}
