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
import com.facebook.buck.core.parser.buildtargetpattern.BuildTargetPattern.Kind
import com.facebook.buck.core.parser.buildtargetpattern.BuildTargetPatternParser
import com.facebook.buck.multitenant.fs.FsAgnosticPath
import com.facebook.buck.multitenant.service.BuildTargets
import com.facebook.buck.multitenant.service.Generation
import com.facebook.buck.multitenant.service.Index
import com.facebook.buck.multitenant.service.RawBuildRule
import com.facebook.buck.query.AllPathsFunction
import com.facebook.buck.query.BuildFileFunction
import com.facebook.buck.query.DepsFunction
import com.facebook.buck.query.InputsFunction
import com.facebook.buck.query.KindFunction
import com.facebook.buck.query.NoopQueryEvaluator
import com.facebook.buck.query.OwnerFunction
import com.facebook.buck.query.QueryEnvironment
import com.facebook.buck.query.QueryException
import com.facebook.buck.query.QueryExpression
import com.facebook.buck.query.QueryFileTarget
import com.facebook.buck.query.RdepsFunction
import com.facebook.buck.query.TestsOfFunction
import com.google.common.base.Suppliers
import com.google.common.collect.ImmutableList
import java.util.function.Predicate
import java.util.function.Supplier

val QUERY_FUNCTIONS: List<QueryEnvironment.QueryFunction<out QueryTarget, UnconfiguredBuildTarget>> = listOf(
        AllPathsFunction<UnconfiguredBuildTarget>(),
        BuildFileFunction<UnconfiguredBuildTarget>(),
        DepsFunction<UnconfiguredBuildTarget>(),
        DepsFunction.FirstOrderDepsFunction<UnconfiguredBuildTarget>(),
        InputsFunction<UnconfiguredBuildTarget>(),
        KindFunction<UnconfiguredBuildTarget>(),
        OwnerFunction<UnconfiguredBuildTarget>(),
        RdepsFunction<UnconfiguredBuildTarget>(),
        TestsOfFunction<UnconfiguredBuildTarget>())

/**
 * Each instance of a [MultitenantQueryEnvironment] is parameterized by a generation and the
 * user's local changes. All queries that are satisfied by this environment are done in the context
 * of that state.
 */
class MultitenantQueryEnvironment(
    private val index: Index,
    private val generation: Generation,
    private val cellToBuildFileName: Map<String, String>
) : QueryEnvironment<UnconfiguredBuildTarget> {
    private val targetEvaluator: Supplier<TargetEvaluator> = Suppliers.memoize {
        TargetEvaluator(index, generation)
    }

    /**
     * Elements in the set returned by this method will be either instances of [QueryFileTarget] or
     * [UnconfiguredBuildTarget]
     */
    fun evaluateQuery(query: String): Set<QueryTarget> {
        val expr = QueryExpression.parse<UnconfiguredBuildTarget>(query, this)
        val evaluator = NoopQueryEvaluator<UnconfiguredBuildTarget>()
        return evaluator.eval<QueryTarget>(expr, this)
    }

    override fun getFunctions(): Iterable<QueryEnvironment.QueryFunction<out QueryTarget, UnconfiguredBuildTarget>> {
        return QUERY_FUNCTIONS
    }

    override fun getTargetEvaluator(): QueryEnvironment.TargetEvaluator {
        return targetEvaluator.get()
    }

    override fun getFwdDeps(
        targets: Iterable<UnconfiguredBuildTarget>
    ): Set<UnconfiguredBuildTarget> = index.getFwdDeps(generation, targets)

    override fun getReverseDeps(
        targets: Iterable<UnconfiguredBuildTarget>
    ): Set<UnconfiguredBuildTarget> = index.getReverseDeps(generation, targets)

    override fun getInputs(target: UnconfiguredBuildTarget): Set<QueryFileTarget> {
        val targetNode = index.getTargetNodeUnsafe(generation, target).targetNode
        return extractInputs(targetNode)
    }

    override fun getTransitiveClosure(targets: Set<UnconfiguredBuildTarget>): Set<UnconfiguredBuildTarget> {
        return index.getTransitiveDeps(generation, targets.asSequence())
    }

    override fun buildTransitiveClosure(targetNodes: MutableSet<out QueryTarget>, maxDepth: Int) {
        // Nothing to do! This method is to populate the QueryEnvironment, but because
        // MultitenantQueryEnvironment is backed by an Index, it is already fully populated.

        // Note: Looking at BuckQueryEnvironment.buildTransitiveClosure(), it seems like we might be able
        // to tighten targetNodes to be Set<UnconfiguredBuildTarget> instead of Set<? extends QueryTarget>.
    }

    override fun getTargetKind(target: UnconfiguredBuildTarget): String {
        val rawBuildRule = index.getTargetNodeUnsafe(generation, target)
        return rawBuildRule.targetNode.ruleType.toString()
    }

    override fun getTestsForTarget(
        target: UnconfiguredBuildTarget
    ): Set<UnconfiguredBuildTarget> {
        val targetNode = index.getTargetNodeUnsafe(generation, target).targetNode
        return extractTests(targetNode)
    }

    override fun getBuildFiles(targets: Set<UnconfiguredBuildTarget>): Set<QueryFileTarget> =
        targets.map { target ->
            val path = FsAgnosticSourcePath(FsAgnosticPath.of(
                    "${target.baseName.substring(2)}/${cellToBuildFileName[target.cell]!!}"))
            QueryFileTarget.of(path)
        }.toSet()

    /**
     * @param files are assumed to be paths relative to the root cell
     */
    override fun getFileOwners(files: ImmutableList<String>): Set<UnconfiguredBuildTarget> {
        val relativePaths = files.asSequence().map { FsAgnosticPath.of(it) }
        val relativePathToTrueBasePath = mutableMapOf<FsAgnosticPath, FsAgnosticPath>()
        val basePathToTargets = mutableMapOf<FsAgnosticPath, List<UnconfiguredBuildTarget>>()

        relativePaths.forEach { relativePath ->
            val basePath = relativePath.dirname()
            val pathAndTargets = index.getTargetsInOwningBuildPackage(generation, basePath)
            if (pathAndTargets != null) {
                val (trueBasePath, targets) = pathAndTargets
                relativePathToTrueBasePath[relativePath] = trueBasePath
                basePathToTargets[trueBasePath] = targets
            }
        }

        val out = mutableSetOf<UnconfiguredBuildTarget>()
        relativePaths.forEach { relativePath ->
            // Note it is possible that there are no owner candidates for the relative path, in
            // which case it will have no entry in the relativePathToTrueBasePath map.
            val basePath = relativePathToTrueBasePath[relativePath] ?: return@forEach
            val candidateTargets = basePathToTargets.getValue(basePath)

            candidateTargets.forEach { candidateTarget ->
                val inputs = getInputs(candidateTarget)
                if (inputs.contains(QueryFileTarget.of(FsAgnosticSourcePath(relativePath)))) {
                    out.add(candidateTarget)
                }
            }
        }
        return out
    }

    override fun getTargetsInAttribute(target: UnconfiguredBuildTarget?, attribute: String?): Set<QueryTarget> {
        TODO("getTargetsInAttribute() not implemented")
    }

    override fun filterAttributeContents(
        target: UnconfiguredBuildTarget?,
        attribute: String?,
        predicate: Predicate<Any>?
    ): Set<Any> {
        TODO("filterAttributeContents() not implemented")
    }

    /** @see Index.getRefs */
    fun getRefs(target: UnconfiguredBuildTarget): List<UnconfiguredBuildTarget> = index.getRefs(generation, target)

    /** Get the rules defined in the build file in the specified directory. */
    fun getRulesInBasePath(basePath: FsAgnosticPath): List<RawBuildRule> {
        val targets = index.getTargetsInBasePath(generation, basePath) ?: listOf()
        val rules = index.getTargetNodes(generation, targets)
        return rules.mapNotNull { it }
    }
}

private class TargetEvaluator(private val index: Index, private val generation: Generation) : QueryEnvironment.TargetEvaluator {
    override fun getType(): QueryEnvironment.TargetEvaluator.Type = QueryEnvironment.TargetEvaluator.Type.IMMEDIATE

    override fun evaluateTarget(target: String): Set<QueryTarget> {
        // TODO: We should probably also support aliases specified via .buckconfig here?
        val buildTargetPattern = try {
            BuildTargetPatternParser.parse(target)
        } catch (e: BuildTargetParseException) {
            throw QueryException(e, "Error trying to parse '$target'")
        }

        // TODO: Cells (and flavors?) need to be supported.
        return when (buildTargetPattern.kind!!) {
            Kind.SINGLE -> {
                val buildTarget = BuildTargets.createBuildTargetFromParts(
                        buildTargetPattern.cell,
                        FsAgnosticPath.of(buildTargetPattern.basePath),
                        buildTargetPattern.targetName)
                setOf(buildTarget)
            }
            Kind.PACKAGE -> {
                val basePath = buildTargetPattern.basePath
                val targets = index.getTargetsInBasePath(generation, FsAgnosticPath.of(basePath))
                        ?: return setOf()
                targets.toSet()
            }
            Kind.RECURSIVE -> {
                val basePath = buildTargetPattern.basePath
                index.getTargetsUnderBasePath(generation, FsAgnosticPath.of(basePath)).toSet()
            }
        }
    }
}

/**
 * HACK! List of build rule attributes that, if present, is known to correspond to a list of
 * SourcePaths. As noted in extractInputs(), this should go away once we move to using
 * RawTargetNodeToTargetNodeFactory.
 */
val SRC_LIST_ATTRIBUTES = listOf("resources", "srcs", "exported_headers")

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
            // TODO(sergeyb): Replace the heuristic below with proper target parser logic.
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

private fun extractTests(targetNode: RawTargetNode): Set<UnconfiguredBuildTarget> {
    val testsAttr = targetNode.attributes["tests"] as? List<*> ?: return setOf()
    val out = HashSet<UnconfiguredBuildTarget>(testsAttr.size)
    val basePath = toBasePath(targetNode.buildTarget)
    testsAttr.forEach { value ->
        val test = value as? String ?: return@forEach
        // TODO(sergeyb): Replace the heuristics below with proper target parser logic.
        when {
            (test.startsWith(":")) -> {
                // Relative build target.
                out.add(BuildTargets.createBuildTargetFromParts(basePath, test.substring(1)))
            }
            (test.contains("//")) -> {
                // Absolute build target.
                out.add(BuildTargets.parseOrThrow(test))
            }
        }
    }
    return out
}
