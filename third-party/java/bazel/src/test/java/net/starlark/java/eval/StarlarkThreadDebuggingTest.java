// Copyright 2018 The Bazel Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package net.starlark.java.eval;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import net.starlark.java.eval.Debug.ReadyToPause;
import net.starlark.java.eval.Debug.Stepping;
import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.Location;
import net.starlark.java.syntax.ParserInput;
import net.starlark.java.syntax.SyntaxError;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests of debugging features of StarlarkThread. */
@RunWith(JUnit4.class)
public class StarlarkThreadDebuggingTest {

  // TODO(adonovan): rewrite these tests at a higher level.

  private static StarlarkThread newThread() {
    return new StarlarkThread(Mutability.create("test"), StarlarkSemantics.DEFAULT);
  }

  // Executes the definition of a trivial function f and returns the function value.
  private static StarlarkFunction defineFunc() throws Exception {
    return (StarlarkFunction)
        Starlark.execFile(
            ParserInput.fromLines("def f(): pass\nf"),
            FileOptions.DEFAULT,
            Module.create(),
            newThread());
  }

  @Test
  public void testStepIntoFunction() throws Exception {
    StarlarkThread thread = newThread();

    ReadyToPause predicate = Debug.stepControl(thread, Stepping.INTO);
    thread.push(defineFunc());

    assertThat(predicate.test(thread)).isTrue();
  }

  @Test
  public void testStepIntoFallsBackToStepOver() {
    // test that when stepping into, we'll fall back to stopping at the next statement in the
    // current frame
    StarlarkThread thread = newThread();

    ReadyToPause predicate = Debug.stepControl(thread, Stepping.INTO);

    assertThat(predicate.test(thread)).isTrue();
  }

  @Test
  public void testStepIntoFallsBackToStepOut() throws Exception {
    // test that when stepping into, we'll fall back to stopping when exiting the current frame
    StarlarkThread thread = newThread();
    thread.push(defineFunc());

    ReadyToPause predicate = Debug.stepControl(thread, Stepping.INTO);
    thread.pop();

    assertThat(predicate.test(thread)).isTrue();
  }

  @Test
  public void testStepOverFunction() throws Exception {
    StarlarkThread thread = newThread();

    ReadyToPause predicate = Debug.stepControl(thread, Stepping.OVER);
    thread.push(defineFunc());

    assertThat(predicate.test(thread)).isFalse();
    thread.pop();
    assertThat(predicate.test(thread)).isTrue();
  }

  @Test
  public void testStepOverFallsBackToStepOut() throws Exception {
    // test that when stepping over, we'll fall back to stopping when exiting the current frame
    StarlarkThread thread = newThread();
    thread.push(defineFunc());

    ReadyToPause predicate = Debug.stepControl(thread, Stepping.OVER);
    thread.pop();

    assertThat(predicate.test(thread)).isTrue();
  }

  @Test
  public void testStepOutOfInnerFrame() throws Exception {
    StarlarkThread thread = newThread();
    thread.push(defineFunc());

    ReadyToPause predicate = Debug.stepControl(thread, Stepping.OUT);

    assertThat(predicate.test(thread)).isFalse();
    thread.pop();
    assertThat(predicate.test(thread)).isTrue();
  }

  @Test
  public void testStepOutOfOutermostFrame() {
    StarlarkThread thread = newThread();

    assertThat(Debug.stepControl(thread, Stepping.OUT)).isNull();
  }

  @Test
  public void testStepControlWithNoSteppingReturnsNull() {
    StarlarkThread thread = newThread();

    assertThat(Debug.stepControl(thread, Stepping.NONE)).isNull();
  }

  @Test
  public void testEvaluateVariableInScope() throws Exception {
    Module module =
        Module.withPredeclared(ImmutableMap.of("a", StarlarkInt.of(1)));

    StarlarkThread thread = newThread();
    Object a = Starlark.execFile(ParserInput.fromLines("a"), FileOptions.DEFAULT, module, thread);
    assertThat(a).isEqualTo(StarlarkInt.of(1));
  }

  @Test
  public void testEvaluateVariableNotInScopeFails() throws Exception {
    Module module = Module.create();

    SyntaxError.Exception e =
        assertThrows(
            SyntaxError.Exception.class,
            () ->
                Starlark.execFile(
                    ParserInput.fromLines("b"), FileOptions.DEFAULT, module, newThread()));

    assertThat(e).hasMessageThat().isEqualTo("name 'b' is not defined");
  }

  @Test
  public void testEvaluateExpressionOnVariableInScope() throws Exception {
    StarlarkThread thread = newThread();
    Module module =
        Module.withPredeclared(
            /*predeclared=*/ ImmutableMap.of("a", "string"));

    assertThat(
            Starlark.execFile(
                ParserInput.fromLines("a.startswith('str')"), FileOptions.DEFAULT, module, thread))
        .isEqualTo(true);
    Starlark.execFile(ParserInput.fromLines("a = 1"), FileOptions.DEFAULT, module, thread);
    assertThat(Starlark.execFile(ParserInput.fromLines("a"), FileOptions.DEFAULT, module, thread))
        .isEqualTo(StarlarkInt.of(1));
  }
}
