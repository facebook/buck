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
import com.facebook.buck.core.model.targetgraph.raw.RawTargetNode
import com.facebook.buck.core.parser.buildtargetpattern.BuildTargetPatternData.Kind
import com.facebook.buck.core.parser.buildtargetpattern.BuildTargetPatternDataParser
import com.facebook.buck.multitenant.fs.FsAgnosticPath
import com.facebook.buck.multitenant.service.Generation
import com.facebook.buck.multitenant.service.Index
import com.facebook.buck.query.DepsFunction
import com.facebook.buck.query.InputsFunction
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
        InputsFunction<UnconfiguredBuildTarget>(),
        KindFunction<UnconfiguredBuildTarget>())

/**
 * Each instance of a [MultitenantQueryEnvironment] is parameterized by a generation and the
 * user's local changes. All queries that are satisfied by this environment are done in the context
 * of that state.
 */
class MultitenantQueryEnvironment(private val index: Index, private val generation: Generation) : QueryEnvironment<UnconfiguredBuildTarget> {
    private val targetEvaluator: Supplier<TargetEvaluator> = Suppliers.memoize {
        TargetEvaluator(index, generation)
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
        index.getFwdDeps(generation, targets, fwdDeps)
        return fwdDeps.build()
    }

    override fun getReverseDeps(targets: Iterable<UnconfiguredBuildTarget>?): MutableSet<UnconfiguredBuildTarget> {
        TODO("getReverseDeps() not implemented")
    }

    override fun getInputs(target: UnconfiguredBuildTarget): Set<QueryFileTarget> {
        val targetNode = requireNotNull(index.getTargetNode(generation, target)).targetNode
        return extractInputs(targetNode)
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
        val rawBuildRule = requireNotNull(index.getTargetNode(generation, target))
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

private class TargetEvaluator(private val index: Index, private val generation: Generation) : QueryEnvironment.TargetEvaluator {
    override fun getType(): QueryEnvironment.TargetEvaluator.Type = QueryEnvironment.TargetEvaluator.Type.IMMEDIATE

    override fun evaluateTarget(target: String): ImmutableSet<QueryTarget> {
        // TODO: We should probably also support aliases specified via .buckconfig here?
        val buildTargetPattern = try {
            BuildTargetPatternDataParser.parse(target)
        } catch (e: BuildTargetParseException) {
            throw QueryException(e, "Error trying to parse '$target'")
        }

        // TODO: Cells (and flavors?) need to be supported.
        return when (buildTargetPattern.kind!!) {
            Kind.SINGLE -> {
                ImmutableSet.of(index.parseBuildTarget(buildTargetPattern.toString()))
            }
            Kind.PACKAGE -> {
                val basePath = buildTargetPattern.basePath
                ImmutableSet.copyOf(index.getTargetsInBasePath(generation, FsAgnosticPath.of(basePath)))
            }
            Kind.RECURSIVE -> {
                val basePath = buildTargetPattern.basePath
                ImmutableSet.copyOf(index.getTargetsUnderBasePath(generation, FsAgnosticPath.of(basePath)))
            }
        }
    }
}

/**
 * HACK! List of build rule attributes that, if present, is known to correspond to a list of
 * SourcePaths. As noted in extractInputs(), this should go away once we move to using
 * RawTargetNodeToTargetNodeFactory.
 */
val SRC_LIST_ATTRIBUTES = listOf("srcs", "exported_headers")

private fun extractInputs(targetNode: RawTargetNode): Set<QueryFileTarget> {
    // Ideally, we would use RawTargetNodeToTargetNodeFactory and invoke its getInputs() method.
    // Currently, that is a difficult thing to do because it would pull in far more dependencies
    // than we can manage cleanly. For now, we use some heuristics so we can at least provide some
    // value.
    val basePath = toBasePath(targetNode.buildTarget)
    val attrs = targetNode.attributes
    val inputs: MutableSet<QueryFileTarget> = mutableSetOf()
    for (attrName in SRC_LIST_ATTRIBUTES) {
        val srcs = attrs[attrName] as? List<*> ?: continue
        for (src in srcs) {
            if (src !is String) {
                continue
            }

            // If "//" appears at the beginning, it is an absolute build target. If it appears in
            // the middle, then it is an absolute build target within a cell.
            // TODO(buck_team): Replace the heuristic below with proper target parser logic.
            if (!src.startsWith(":") && !src.contains("//")) {
                val fullPath = basePath.resolve(FsAgnosticPath.of(src))
                inputs.add(QueryFileTarget.of(FsAgnosticSourcePath(fullPath)))
            }
        }
    }
    return inputs
}

private fun toBasePath(target: UnconfiguredBuildTarget): FsAgnosticPath {
    return FsAgnosticPath.of(target.baseName.substring(2))
}
