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

package com.facebook.buck.multitenant.importer

import com.facebook.buck.core.model.ImmutableUnconfiguredBuildTarget
import com.facebook.buck.core.model.RuleType
import com.facebook.buck.core.model.UnconfiguredBuildTarget
import com.facebook.buck.core.model.targetgraph.raw.RawTargetNode
import com.facebook.buck.multitenant.fs.FsAgnosticPath
import com.facebook.buck.multitenant.service.BuildPackage
import com.facebook.buck.multitenant.service.Changes
import com.facebook.buck.multitenant.service.IndexAppender
import com.facebook.buck.multitenant.service.RawBuildRule
import com.facebook.buck.rules.visibility.VisibilityPattern
import com.facebook.buck.util.json.ObjectMappers
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import java.io.InputStream

/**
 * Ultimately, we would like to use kotlinx.serialization for this, but we are currently blocked by
 * https://youtrack.jetbrains.com/issue/KT-30998.
 */
fun populateIndexFromStream(
        indexAppender: IndexAppender,
        parser: (target: String) -> UnconfiguredBuildTarget,
        stream: InputStream): List<String> = ObjectMappers.createParser(stream)
        .enable(JsonParser.Feature.ALLOW_COMMENTS)
        .enable(JsonParser.Feature.ALLOW_TRAILING_COMMA)
        .readValueAsTree<JsonNode>()
        .asSequence().map { commit ->
            val hash = commit.get("commit").asText()
            val added = toBuildPackages(parser, commit.get("added"))
            val modified = toBuildPackages(parser, commit.get("modified"))
            val removed = toRemovedPackages(commit.get("removed"))
            val changes = Changes(added, modified, removed)
            indexAppender.addCommitData(hash, changes)
            hash
        }.toList()

/** Parses an "ordinary" fully-qualified build target with no cells or flavors. */
fun parseOrdinaryBuildTarget(target: String): UnconfiguredBuildTarget {
    if (!target.startsWith("//")) {
        throw IllegalArgumentException("target '$target' did not start with //")
    }

    val index = target.lastIndexOf(':')
    if (index < 0) {
        throw IllegalArgumentException("target '$target' did not contain a colon")
    }

    return ImmutableUnconfiguredBuildTarget.of(
            "", target.substring(0, index), target.substring(index + 1), UnconfiguredBuildTarget.NO_FLAVORS
    )
}

val FAKE_RULE_TYPE: RuleType = RuleTypeFactory.createBuildRule("fake_rule")

private fun toBuildPackages(parser: (target: String) -> UnconfiguredBuildTarget, node: JsonNode?): List<BuildPackage> {
    if (node == null) {
        return listOf()
    }

    return node.map { buildPackageItem ->
        val path = FsAgnosticPath.of(buildPackageItem.get("path").asText())
        val rulesAttr = buildPackageItem.get("rules")
        val rules = rulesAttr.elements().asSequence().map { rule ->
            var name: String? = null
            var ruleType: String? = null
            val deps = mutableSetOf<String>()
            val attrs = ImmutableMap.builder<String, Any>()
            for (field in rule.fields()) {
                when (field.key) {
                    "name" -> name = field.value.asText()
                    "buck.type" -> ruleType = field.value.asText()
                    "deps" -> deps.addAll(field.value.asSequence().map { it.asText() })
                    else -> {
                        // Properties that start with "buck." have a special meaning that must be
                        // handled explicitly.
                        if (!field.key.startsWith("buck.")) {
                            attrs.put(field.key, normalizeJsonValue(field.value))
                        }
                    }
                }
            }
            requireNotNull(name)
            requireNotNull(ruleType)
            val buildTarget = parser("//$path:$name")
            createRawRule(parser, buildTarget, ruleType, deps, attrs.build())
        }.toSet()
        BuildPackage(path, rules)
    }
}

private fun normalizeJsonValue(value: JsonNode): Any {
    // Note that if we need to support other values, such as null or Object, we will add support for
    // them as needed.
    return when {
        value.isBoolean -> value.asBoolean()
        value.isTextual -> value.asText()
        value.isLong -> value.asLong()
        value.isDouble -> value.asDouble()
        value.isArray -> (value as ArrayNode).map { normalizeJsonValue(it) }
        else -> throw IllegalArgumentException("normalizeJsonValue() not supported for $value")
    }
}

private fun toRemovedPackages(node: JsonNode?): List<FsAgnosticPath> {
    if (node == null) {
        return listOf()
    }

    return node.asSequence().map { FsAgnosticPath.of(it.asText()) }.toList()
}

private fun createRawRule(
        parser: (target: String) -> UnconfiguredBuildTarget,
        target: UnconfiguredBuildTarget,
        ruleType: String,
        deps: Set<String>,
        attrs: ImmutableMap<String, Any>): RawBuildRule {
    val node = ServiceRawTargetNode(target, RuleTypeFactory.createBuildRule(ruleType), attrs)
    return RawBuildRule(node, deps.map { parser(it) }.toSet())
}

/**
 * Simplified implementation of [RawTargetNode] that is sufficient for the multitenant service's
 * needs.
 */
data class ServiceRawTargetNode(
        private val buildTarget: UnconfiguredBuildTarget,
        private val ruleType: RuleType,
        private val attributes: ImmutableMap<String, Any>) : RawTargetNode {

    override fun getBuildTarget(): UnconfiguredBuildTarget = buildTarget

    override fun getRuleType(): RuleType = ruleType

    override fun getAttributes(): ImmutableMap<String, Any>? = attributes

    override fun getVisibilityPatterns(): ImmutableSet<VisibilityPattern> {
        return ImmutableSet.of()
    }

    override fun getWithinViewPatterns(): ImmutableSet<VisibilityPattern> {
        return ImmutableSet.of()
    }
}
