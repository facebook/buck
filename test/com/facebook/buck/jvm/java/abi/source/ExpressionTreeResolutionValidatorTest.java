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

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;

@RunWith(CompilerTreeApiTestRunner.class)
public class ExpressionTreeResolutionValidatorTest extends CompilerTreeApiTest {
  @Test
  public void testSimpleClassPasses() throws IOException {
    final Iterable<? extends CompilationUnitTree> compilationUnits =
        compile("public class Foo { }");

    ExpressionTreeResolutionValidator validator =
        new ExpressionTreeResolutionValidator(javacTask, treeResolver);
    compilationUnits.forEach(cu -> validator.validate(cu, null));
    // Expect no exceptions
  }
}
