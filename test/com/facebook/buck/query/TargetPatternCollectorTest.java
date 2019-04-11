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

package com.facebook.buck.query;

import static org.junit.Assert.assertThat;

import com.facebook.buck.query.QueryEnvironment.Argument;
import com.google.common.collect.ImmutableList;
import java.util.Collection;
import java.util.HashSet;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Test;

public class TargetPatternCollectorTest {
  private Collection<String> collectedLiterals = new HashSet<>();
  private QueryExpression.Visitor collector = new TargetPatternCollector(collectedLiterals);

  @After
  public void tearDown() {
    collectedLiterals.clear();
  }

  @Test
  public void singleLiteral() {
    TargetLiteral.of("foo").traverse(collector);
    assertThat(collectedLiterals, Matchers.contains("foo"));
  }

  @Test
  public void emptySet() {
    SetExpression.of(ImmutableList.of()).traverse(collector);
    assertThat(collectedLiterals, Matchers.empty());
  }

  @Test
  public void singletonSet() {
    SetExpression.of(ImmutableList.of(TargetLiteral.of("foo"))).traverse(collector);
    assertThat(collectedLiterals, Matchers.contains("foo"));
  }

  @Test
  public void complexExpression() {
    BinaryOperatorExpression.of(
            AbstractBinaryOperatorExpression.Operator.UNION,
            ImmutableList.of(
                new ImmutableFunctionExpression<>(
                    new DepsFunction(), ImmutableList.of(Argument.of(TargetLiteral.of("foo")))),
                SetExpression.of(
                    ImmutableList.of(TargetLiteral.of("bar"), TargetLiteral.of("baz")))))
        .traverse(collector);
    assertThat(collectedLiterals, Matchers.containsInAnyOrder("foo", "bar", "baz"));
  }
}
