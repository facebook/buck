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

import com.facebook.buck.core.model.UnconfiguredBuildTarget
import com.facebook.buck.core.path.ForwardRelativePath
import com.facebook.buck.multitenant.fs.FsAgnosticPath
import com.facebook.buck.util.json.ObjectMappers
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.collect.ImmutableMap
import java.io.InputStream
import java.io.OutputStream

/**
 * Read commit state from JSON and populate index with data
 * @return List of hashes of all commits processed
 */
fun populateIndexFromStream(
    indexAppender: IndexAppender,
    stream: InputStream,
    packageParser: (JsonNode) -> BuildPackage
): List<String> {
    val parser = ObjectMappers.createParser(stream)
        .enable(JsonParser.Feature.ALLOW_COMMENTS)
        .enable(JsonParser.Feature.ALLOW_TRAILING_COMMA)

    val result = mutableListOf<String>()

    // Loading a big JSON file with `readValueAsTree` is slow and very memory hungry. We use
    // a mixed approach - stream load JSON by reading token sequentially up to the package
    // definition and use `readValueAsTree` to load the package itself and transform it into
    // `BuildPackage`, allowing garbage collector to pick up JsonNode afterwards as we progress
    // with other packages.
    // Granularity can be improved by streaming each target individually if packages are too
    // big, at this moment it seems to be good enough.

    // top level data structure is an array of commits
    check(parser.nextToken() == JsonToken.START_ARRAY)
    while (parser.nextToken() != JsonToken.END_ARRAY) {
        check(parser.currentToken == JsonToken.START_OBJECT)
        var commit: String? = null
        val added = mutableListOf<BuildPackage>()
        val modified = mutableListOf<BuildPackage>()
        val removed = mutableListOf<ForwardRelativePath>()
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            // commit data is defined with 4 possible fields: commit, added, modified, removed
            // 'added' and 'modified' contain a list of packages
            // 'removed' contain a list of paths denoting removed packages
            check(parser.currentToken == JsonToken.FIELD_NAME)
            when (val fieldName = parser.currentName()) {
                "commit" -> {
                    check(parser.nextToken() == JsonToken.VALUE_STRING)
                    commit = parser.valueAsString
                }
                "added" -> parsePackages(parser, added, packageParser)
                "modified" -> parsePackages(parser, modified, packageParser)
                "removed" -> parsePaths(parser, removed)
                else -> throw IllegalStateException("Unrecognized field $fieldName")
            }
        }
        val commitRequired = requireNotNull(commit)
        indexAppender.addCommitData(commitRequired, BuildPackageChanges(added, modified, removed))
        result.add(commitRequired)
    }
    return result
}

/**
 * Read packages from JSON
 */
fun parsePackagesFromStream(
    stream: InputStream,
    packageParser: (JsonNode) -> BuildPackage
): MutableList<BuildPackage> {
    val parser = createParser(stream)
    val packages = mutableListOf<BuildPackage>()
    parsePackages(parser, packages, packageParser)
    return packages
}

/**
 * Write packages to JSON
 */
fun serializePackagesToStream(packages: List<BuildPackage>, stream: OutputStream) {
    ObjectMappers.WRITER.without(JsonGenerator.Feature.AUTO_CLOSE_TARGET)
        .writeValue(stream, packages)
}

/**
 * Write paths to JSON
 */
fun serializePathsToStream(paths: List<ForwardRelativePath>, stream: OutputStream) {
    ObjectMappers.WRITER.without(JsonGenerator.Feature.AUTO_CLOSE_TARGET).writeValue(stream, paths)
}

private fun createParser(stream: InputStream): JsonParser {
    return ObjectMappers.createParser(stream)
        .enable(JsonParser.Feature.ALLOW_COMMENTS)
        .enable(JsonParser.Feature.ALLOW_TRAILING_COMMA)
}

private fun parsePaths(parser: JsonParser, list: MutableList<ForwardRelativePath>) {
    check(parser.nextToken() == JsonToken.START_ARRAY)
    val removeNode = parser.readValueAsTree<JsonNode>()
    // 'removeNode' is an Array node, iterating through which gives paths of removed packages
    if (removeNode !is NullNode) {
        list.addAll(removeNode.map { p -> FsAgnosticPath.of(p.asText()) })
    }
}

private fun parsePackages(
    parser: JsonParser,
    list: MutableList<BuildPackage>,
    packageParser: (JsonNode) -> BuildPackage
) {
    check(parser.nextToken() == JsonToken.START_ARRAY)
    while (parser.nextToken() != JsonToken.END_ARRAY) {
        val packageNode = parser.readValueAsTree<JsonNode>()
        // 'removeNode' is an Object which is a fully parsed package node
        // with the same structure as UnconfiguredTargetNodeWithDepsPackage
        if (packageNode !is NullNode) {
            list.add(packageParser(packageNode))
        }
    }
}

/**
 * Convert Json produced by multitenant service to [BuildPackage]
 */
fun multitenantJsonToBuildPackageParser(nodes: JsonNode): BuildPackage {
    return ObjectMappers.READER_INTERNED.forType(BuildPackage::class.java).readValue(nodes)
}

/**
 * Convert Json produced by BUCK to [BuildPackage]
 */
fun buckJsonToBuildPackageParser(nodes: JsonNode): BuildPackage {
    val path = FsAgnosticPath.of(nodes.toText("path"))
    val rules = nodes.get("nodes").fields().asSequence().map { (name, rule) ->
        var ruleType: String? = null
        val deps = mutableSetOf<String>()
        val attrs = ImmutableMap.builder<String, Any>()
        for (field in rule.fields()) {
            when (field.key) {
                "attributes" -> {
                    for (attr in field.value.fields()) {
                        attrs.put(attr.key.intern(), normalizeJsonValue(attr.value))
                        if (attr.key == "buck.type") {
                            ruleType = attr.value.asText()
                        }
                    }
                }
                "deps" -> deps.addAll(field.value.asSequence().map { it.asText() })
            }
        }
        requireNotNull(ruleType)
        val buildTarget = BuildTargets.createBuildTargetFromParts(path, name)
        val depsAsTargets = deps.map { BuildTargets.parseOrThrow(it) }.toSet()
        createRawRule(buildTarget, ruleType, depsAsTargets, attrs.build())
    }.toSet()

    val errors = nodes.mapIterable("errors") { error ->
        BuildPackageParsingError(error.toText("message"),
            error.mapIterable("stacktrace") { it.asText() }?.toList() ?: listOf())
    }?.toList() ?: listOf()

    val includes = nodes.mapIterable("includes") {
        FsAgnosticPath.of(it.asText())
    }?.toHashSet() ?: hashSetOf()

    return BuildPackage(
        buildFileDirectory = path,
        rules = rules,
        errors = errors,
        includes = includes
    )
}

private fun JsonNode.toText(nodeName: String): String {
    return get(nodeName).asText()
}

private fun <R> JsonNode.mapIterable(
    iterableNodeName: String,
    transform: (JsonNode) -> R
): Sequence<R>? {
    return get(iterableNodeName)?.elements()?.asSequence()?.map(transform)
}

/**
 * Parse Json object into a primitive type object
 * Maps and arrays are parsed recursively
 * All strings are interned
 */
fun normalizeJsonValue(value: JsonNode): Any {
    // Note that if we need to support other values, such as null or Object, we will add support for
    // them as needed.

    // We intern all the strings here. It is not very well measured the impact of interning here
    // as those strings are attribute values and cardinality of those is not well known. We still
    // intern because it is only used during loading the data for multitenant service and thus
    // cheap to do. This could be reconsidered later.
    return when {
        value.isBoolean -> value.asBoolean()
        value.isTextual -> value.asText().intern()
        value.isInt -> value.asInt()
        value.isLong -> value.asLong()
        value.isDouble -> value.asDouble()
        value.isArray -> (value as ArrayNode).map {
            normalizeJsonValue(it)
        }
        value.isObject -> (value as ObjectNode).fields().asSequence().map {
            it.key.intern() to normalizeJsonValue(it.value)
        }.toMap()
        else -> value.asText().intern()
    }
}

private fun createRawRule(
    target: UnconfiguredBuildTarget,
    ruleType: String,
    deps: Set<UnconfiguredBuildTarget>,
    attrs: ImmutableMap<String, Any>
): RawBuildRule {
    val node = ServiceUnconfiguredTargetNode(target, RuleTypeFactory.createBuildRule(ruleType), attrs)
    return RawBuildRule(node, deps)
}
