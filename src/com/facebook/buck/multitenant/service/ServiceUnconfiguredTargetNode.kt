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

package com.facebook.buck.multitenant.service

import com.facebook.buck.core.model.RuleType
import com.facebook.buck.core.model.UnconfiguredBuildTarget
import com.facebook.buck.core.model.targetgraph.raw.UnconfiguredTargetNode
import com.facebook.buck.rules.visibility.VisibilityPattern
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import java.io.IOException

/**
 * Simplified implementation of [UnconfiguredTargetNode] that is sufficient for the multitenant service's
 * needs.
 */
@JsonDeserialize(using = ServiceUnconfiguredTargetNodeDeserializer::class)
data class ServiceUnconfiguredTargetNode(
    private val buildTarget: UnconfiguredBuildTarget,
    private val ruleType: RuleType,
    private val attributes: ImmutableMap<String, Any>
) : UnconfiguredTargetNode {

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

/**
 * Custom JSON deserializer for [ServiceUnconfiguredTargetNode] which interns all strings used
 * in [ServiceUnconfiguredTargetNode.attributes]
 */
class ServiceUnconfiguredTargetNodeDeserializer : JsonDeserializer<ServiceUnconfiguredTargetNode>() {

    @Throws(IOException::class) override fun deserialize(
        parser: JsonParser,
        deserializationContext: DeserializationContext
    ): ServiceUnconfiguredTargetNode {

        var buildTarget: UnconfiguredBuildTarget? = null
        var ruleType: RuleType? = null
        var attributes: ImmutableMap<String, Any>? = null

        while (parser.nextToken() != JsonToken.END_OBJECT) {
            check(parser.currentToken == JsonToken.FIELD_NAME)
            when (parser.currentName()) {
                "buildTarget" -> {
                    check(parser.nextToken() == JsonToken.VALUE_STRING)
                    val deserializer = deserializationContext.findRootValueDeserializer(
                        deserializationContext.getTypeFactory().constructType(UnconfiguredBuildTarget::class.java))
                    @Suppress("UnsafeCast")
                    buildTarget = deserializer.deserialize(parser, deserializationContext) as UnconfiguredBuildTarget
                }
                "ruleType" -> {
                    check(parser.nextToken() == JsonToken.START_OBJECT)
                    val deserializer = deserializationContext.findRootValueDeserializer(
                        deserializationContext.getTypeFactory().constructType(RuleType::class.java))
                    @Suppress("UnsafeCast")
                    ruleType = deserializer.deserialize(parser, deserializationContext) as RuleType
                }
                "attributes" -> {
                    check(parser.nextToken() == JsonToken.START_OBJECT)
                    attributes = parseAndInternAttributes(parser)
                }
                else -> {
                    // just skip unknown fields
                    parser.nextToken()
                    parser.readValueAsTree<JsonNode>()
                }
            }
        }

        return ServiceUnconfiguredTargetNode(
            buildTarget = checkNotNull(buildTarget) { "Target node should have 'buildTarget'"},
            ruleType = checkNotNull(ruleType) { "Target node should have 'ruleType'"},
            attributes = checkNotNull(attributes) { "Target node should have 'attributes'"})
    }

    private fun parseAndInternAttributes(parser: JsonParser): ImmutableMap<String, Any> {
        val node = parser.readValueAsTree<JsonNode>()
        val builder = ImmutableMap.builder<String, Any>()
        for (attr in node.fields()) {
            builder.put(attr.key.intern(), normalizeJsonValue(attr.value))
        }
        return builder.build()
    }
}
