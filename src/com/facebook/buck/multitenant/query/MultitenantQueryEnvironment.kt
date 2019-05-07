/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.multitenant.query

import com.facebook.buck.core.exceptions.BuildTargetParseException
import com.facebook.buck.core.model.QueryTarget
import com.facebook.buck.core.model.UnconfiguredBuildTarget
import com.facebook.buck.multitenant.service.Commit
import com.facebook.buck.multitenant.service.Index
import com.facebook.buck.query.DepsFunction
import com.facebook.buck.query.KindFunction
import com.facebook.buck.query.NoopQueryEvaluator
import com.facebook.buck.query.QueryEnvironment
import com.facebook.buck.query.QueryException
import com.facebook.buck.query.QueryExpression
import com.facebook.buck.query.QueryFileTarget
import com.google.common.base.Suppliers
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableSet
import java.util.function.Predicate
import java.util.function.Supplier

val QUERY_FUNCTIONS: List<QueryEnvironment.QueryFunction<out QueryTarget, UnconfiguredBuildTarget>> = listOf(
        DepsFunction<UnconfiguredBuildTarget>(),
        DepsFunction.FirstOrderDepsFunction<UnconfiguredBuildTarget>(),
        KindFunction<UnconfiguredBuildTarget>())

/**
 * Each instance of a [MultitenantQueryEnvironment] is parameterized by a base commit and the
 * user's local changes. All queries that are satisfied by this environment are done in the context
 * of that state.
 */
class MultitenantQueryEnvironment(private val index: Index, private val commit: Commit) : QueryEnvironment<UnconfiguredBuildTarget> {
    private val targetEvaluator: Supplier<TargetEvaluator> = Suppliers.memoize {
        TargetEvaluator(index, commit)
    }

    fun evaluateQuery(query: String): ImmutableSet<UnconfiguredBuildTarget> {
        val expr = QueryExpression.parse<UnconfiguredBuildTarget>(query, this)
        val evaluator = NoopQueryEvaluator<UnconfiguredBuildTarget>()
        return evaluator.eval<UnconfiguredBuildTarget>(expr, this)
    }

    override fun getFunctions(): Iterable<QueryEnvironment.QueryFunction<out QueryTarget, UnconfiguredBuildTarget>> {
        return QUERY_FUNCTIONS
    }

    override fun getTargetEvaluator(): QueryEnvironment.TargetEvaluator {
        return targetEvaluator.get()
    }

    override fun getFwdDeps(targets: Iterable<UnconfiguredBuildTarget>): ImmutableSet<UnconfiguredBuildTarget> {
        val fwdDeps = ImmutableSet.Builder<UnconfiguredBuildTarget>()
        index.acquireReadLock().use {
            index.getFwdDeps(it, commit, targets, fwdDeps)
        }
        return fwdDeps.build()
    }

    override fun getReverseDeps(targets: Iterable<UnconfiguredBuildTarget>?): MutableSet<UnconfiguredBuildTarget> {
        TODO("getReverseDeps() not implemented")
    }

    override fun getInputs(target: UnconfiguredBuildTarget?): MutableSet<QueryFileTarget> {
        TODO("getInputs() not implemented")
    }

    override fun getTransitiveClosure(targets: MutableSet<UnconfiguredBuildTarget>?): MutableSet<UnconfiguredBuildTarget> {
        TODO("getTransitiveClosure() not implemented")
    }

    override fun buildTransitiveClosure(targetNodes: MutableSet<out QueryTarget>, maxDepth: Int) {
        // Nothing to do! This method is to populate the QueryEnvironment, but because
        // MultitenantQueryEnvironment is backed by an Index, it is already fully populated.

        // Note: Looking at BuckQueryEnvironment.buildTransitiveClosure(), it seems like we might be able
        // to tighten targetNodes to be Set<UnconfiguredBuildTarget> instead of Set<? extends QueryTarget>.
    }

    override fun getTargetKind(target: UnconfiguredBuildTarget): String {
        val rawBuildRule = requireNotNull(index.getTargetNode(commit, target))
        return rawBuildRule.targetNode.ruleType.toString()
    }

    override fun getTestsForTarget(target: UnconfiguredBuildTarget?): ImmutableSet<UnconfiguredBuildTarget> {
        TODO("getTestsForTarget() not implemented")
    }

    override fun getBuildFiles(targets: MutableSet<UnconfiguredBuildTarget>?): ImmutableSet<QueryFileTarget> {
        TODO("getBuildFiles() not implemented")
    }

    override fun getFileOwners(files: ImmutableList<String>?): ImmutableSet<UnconfiguredBuildTarget> {
        TODO("getFileOwners() not implemented")
    }

    override fun getTargetsInAttribute(target: UnconfiguredBuildTarget?, attribute: String?): ImmutableSet<out QueryTarget> {
        TODO("getTargetsInAttribute() not implemented")
    }

    override fun filterAttributeContents(target: UnconfiguredBuildTarget?, attribute: String?, predicate: Predicate<Any>?): ImmutableSet<Any> {
        TODO("filterAttributeContents() not implemented")
    }
}

private class TargetEvaluator(private val index: Index, private val commit: Commit) : QueryEnvironment.TargetEvaluator {
    override fun getType(): QueryEnvironment.TargetEvaluator.Type = QueryEnvironment.TargetEvaluator.Type.IMMEDIATE

    override fun evaluateTarget(target: String?): ImmutableSet<QueryTarget> {
        requireNotNull(target)

        // Check to see if it is a wildcard pattern.
        if (target.startsWith("//") && target.endsWith("/...")) {
            val basePath = if (target.length == 5) "" else target.substring(2, target.length - 4)
            return ImmutableSet.copyOf(index.getTargetsUnderBasePath(commit, basePath))
        }

        // TODO: We should probably also support aliases specified via .buckconfig here?

        val unconfiguredBuildTarget: UnconfiguredBuildTarget
        try {
            unconfiguredBuildTarget = index.buildTargetParser(target)
        } catch (e: BuildTargetParseException) {
            throw QueryException(e, "Error trying to parse '${target}'")
        }
        return ImmutableSet.of(unconfiguredBuildTarget)
    }
}
