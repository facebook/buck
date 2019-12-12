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

package com.facebook.buck.intellij.ideabuck.format;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class DependenciesOptimizerTest {

  @Test
  public void ordering() {
    /*
    The sort ordering should be:

    (1)  Local targets (beginning with ':') appear first, sorted alphabetically first
    (2)  Same-cell targets... (beginning with '//') appear next, sorted...
    (2a)  ...alphabetically by path segments, where segments are divided by '/'
          Examples: ('/a:z' < '/aa:a') and ('/a/b' < '/a/b/c')
    (2b)  ...then alphabetically by targets (after ':'), with implicit targets first
          Examples: ('//foo' < '//foo:bar' < '//foo:baz')
    (3)  For cross-cell targets, sort alphabetically by cell-name, then as per rules of (2)
     */

    List<String> expected =
        Arrays.asList(
            ":cmd",
            ":lib",
            "//:cmd",
            "//:lib",
            "//foo:cmd",
            "//foo:lib",
            "//foo/bar:cmd",
            "//foo/bar:lib",
            "//foo.applet:cmd",
            "//foo.applet/resources:img",
            "//foo2/bar:cmd",
            "//foo2/bar:lib",
            "//foo_fighter:cmd",
            "//foo_fighter:lib",
            "//food:cmd",
            "//food:lib",
            "baz//moar:moar",
            "baz//moar:stuff",
            "baz//moar/stuff:here",
            "qux//:moar",
            "qux//:stuff",
            "qux//stuff:here");
    List<String> actual = new ArrayList<String>(expected);
    Collections.reverse(actual); // start opposite order to verify sort is stable
    Collections.sort(actual, DependenciesOptimizer.sortOrder());
    Assert.assertEquals(expected, actual);
  }
}
