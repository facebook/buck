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

package com.facebook.buck.rules.query;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.AndroidLibraryBuilder;
import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetFactory;
import com.facebook.buck.core.parser.buildtargetparser.UnconfiguredBuildTargetFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.java.JavaBinaryRuleBuilder;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.query.QueryBuildTarget;
import com.facebook.buck.query.QueryException;
import com.facebook.buck.query.QueryExpression;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Test;

public class QueryCacheTest {

  private static final TypeCoercerFactory TYPE_COERCER_FACTORY = new DefaultTypeCoercerFactory();

  @Test
  public void cacheQueryResults() throws QueryException {
    Query q1 = Query.of("deps(:a) union deps(:c) except (deps(:a) intersect classpath(deps(:f)))");
    Query q2 = Query.of("deps(:a) ^ classpath(deps(:f))");
    Query q3 = Query.of("kind(binary, deps(:a))");
    Query q4 = Query.of("attrfilter(deps, :e, set(:b :c :f :g))");
    Query q5 = Query.of("kind(library, deps(:a) + deps(:c))");
    Query q6 = Query.of("deps(:c)");

    BuildTarget foo = BuildTargetFactory.newInstance("//:foo");
    BuildTarget bar = BuildTargetFactory.newInstance("//:bar");
    BuildTarget baz = BuildTargetFactory.newInstance("//:baz");

    BuildTarget targetA = BuildTargetFactory.newInstance("//app:a");
    BuildTarget targetB = BuildTargetFactory.newInstance("//app:b");
    BuildTarget targetC = BuildTargetFactory.newInstance("//app:c");
    BuildTarget targetD = BuildTargetFactory.newInstance("//app:d");
    BuildTarget targetE = BuildTargetFactory.newInstance("//app:e");
    BuildTarget targetF = BuildTargetFactory.newInstance("//app:f");
    BuildTarget targetG = BuildTargetFactory.newInstance("//app:g");
    BuildTarget targetH = BuildTargetFactory.newInstance("//app:h");

    /*
     *   a       f
     *  / \     / \
     * b   c   g   h
     *  \ / \ /
     *   d   e
     */
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            AndroidLibraryBuilder.createBuilder(foo)
                .setDepsQuery(q1)
                .setProvidedDepsQuery(q2)
                .build(),
            AndroidLibraryBuilder.createBuilder(bar)
                .setDepsQuery(q3)
                .setProvidedDepsQuery(q4)
                .build(),
            AndroidLibraryBuilder.createBuilder(baz)
                .setDepsQuery(q5)
                .setProvidedDepsQuery(q6)
                .build(),
            JavaLibraryBuilder.createBuilder(targetA).addDep(targetB).addDep(targetC).build(),
            JavaLibraryBuilder.createBuilder(targetB).addDep(targetD).build(),
            JavaLibraryBuilder.createBuilder(targetC).addDep(targetD).addDep(targetE).build(),
            new JavaBinaryRuleBuilder(targetD).build(),
            new JavaBinaryRuleBuilder(targetE).build(),
            JavaLibraryBuilder.createBuilder(targetF).addDep(targetG).addDep(targetH).build(),
            JavaLibraryBuilder.createBuilder(targetG).addDep(targetE).build(),
            JavaLibraryBuilder.createBuilder(targetH).build());

    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);

    GraphEnhancementQueryEnvironment env =
        new GraphEnhancementQueryEnvironment(
            Optional.of(graphBuilder),
            Optional.of(targetGraph),
            TYPE_COERCER_FACTORY,
            TestCellPathResolver.get(new FakeProjectFilesystem()),
            new ParsingUnconfiguredBuildTargetFactory(),
            targetA.getBaseName(),
            ImmutableSet.of());

    QueryCache cache = new QueryCache();

    assertFalse(cache.isPresent(targetGraph, env, q1));
    assertFalse(cache.isPresent(targetGraph, env, q2));
    assertFalse(cache.isPresent(targetGraph, env, q3));
    assertFalse(cache.isPresent(targetGraph, env, q4));
    assertFalse(cache.isPresent(targetGraph, env, q5));
    assertFalse(cache.isPresent(targetGraph, env, q6));

    assertThat(
        cache.getQueryEvaluator(targetGraph).eval(QueryExpression.parse(q1.getQuery(), env), env),
        Matchers.containsInAnyOrder(
            QueryBuildTarget.of(targetA),
            QueryBuildTarget.of(targetB),
            QueryBuildTarget.of(targetC),
            QueryBuildTarget.of(targetD)));

    assertTrue(cache.isPresent(targetGraph, env, q1));
    assertTrue(cache.isPresent(targetGraph, env, q2));
    assertFalse(cache.isPresent(targetGraph, env, q3));
    assertFalse(cache.isPresent(targetGraph, env, q4));
    assertFalse(cache.isPresent(targetGraph, env, q5));
    assertTrue(cache.isPresent(targetGraph, env, q6));
  }

  @Test
  public void dynamicDeps() throws QueryException {
    Query declared = Query.of("$declared_deps");

    BuildTarget fooTarget = BuildTargetFactory.newInstance("//:foo");
    BuildTarget barTarget = BuildTargetFactory.newInstance("//:bar");

    BuildTarget targetA = BuildTargetFactory.newInstance("//app:a");
    BuildTarget targetB = BuildTargetFactory.newInstance("//app:b");

    TargetNode<?> foo =
        AndroidLibraryBuilder.createBuilder(fooTarget)
            .addDep(targetA)
            .setDepsQuery(declared)
            .build();
    TargetNode<?> bar =
        AndroidLibraryBuilder.createBuilder(barTarget)
            .addDep(targetB)
            .setDepsQuery(declared)
            .build();

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            foo,
            bar,
            JavaLibraryBuilder.createBuilder(targetA).build(),
            JavaLibraryBuilder.createBuilder(targetB).build());

    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    UnconfiguredBuildTargetFactory unconfiguredBuildTargetFactory =
        new ParsingUnconfiguredBuildTargetFactory();

    GraphEnhancementQueryEnvironment fooEnv =
        new GraphEnhancementQueryEnvironment(
            Optional.of(graphBuilder),
            Optional.of(targetGraph),
            TYPE_COERCER_FACTORY,
            TestCellPathResolver.get(new FakeProjectFilesystem()),
            unconfiguredBuildTargetFactory,
            fooTarget.getBaseName(),
            foo.getDeclaredDeps());

    GraphEnhancementQueryEnvironment barEnv =
        new GraphEnhancementQueryEnvironment(
            Optional.of(graphBuilder),
            Optional.of(targetGraph),
            TYPE_COERCER_FACTORY,
            TestCellPathResolver.get(new FakeProjectFilesystem()),
            unconfiguredBuildTargetFactory,
            barTarget.getBaseName(),
            bar.getDeclaredDeps());

    QueryCache cache = new QueryCache();

    assertFalse(cache.isPresent(targetGraph, fooEnv, declared));
    assertFalse(cache.isPresent(targetGraph, barEnv, declared));

    assertThat(
        cache
            .getQueryEvaluator(targetGraph)
            .eval(QueryExpression.parse(declared.getQuery(), fooEnv), fooEnv),
        Matchers.contains(QueryBuildTarget.of(targetA)));

    assertTrue(cache.isPresent(targetGraph, fooEnv, declared));
    assertFalse(cache.isPresent(targetGraph, barEnv, declared));

    assertThat(
        cache
            .getQueryEvaluator(targetGraph)
            .eval(QueryExpression.parse(declared.getQuery(), barEnv), barEnv),
        Matchers.contains(QueryBuildTarget.of(targetB)));

    assertTrue(cache.isPresent(targetGraph, fooEnv, declared));
    assertTrue(cache.isPresent(targetGraph, barEnv, declared));
  }
}
