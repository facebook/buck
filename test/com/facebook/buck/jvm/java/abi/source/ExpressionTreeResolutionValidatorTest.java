/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.jvm.java.abi.source;

import com.facebook.buck.testutil.CompilerTreeApiTestRunner;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskListener;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;

import javax.tools.Diagnostic;

@RunWith(CompilerTreeApiTestRunner.class)
public class ExpressionTreeResolutionValidatorTest extends CompilerTreeApiTest {
  @Test
  public void testSimpleClassPasses() throws IOException {
    compileWithValidation("public class Foo { }");

    // Expect no exceptions
  }

  protected Iterable<? extends CompilationUnitTree> compileWithValidation(
      String source) throws IOException {
    return compile(
        source,
        // A side effect of our hacky test class loader appears to be that this only works if
        // it's NOT a lambda. LoL.
        new TaskListenerFactory() {
          @Override
          public TaskListener newTaskListener(JavacTask task) {
            return new ValidatingTaskListener(task, Diagnostic.Kind.ERROR);
          }
        });
  }
}
