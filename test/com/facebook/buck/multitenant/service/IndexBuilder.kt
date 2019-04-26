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

package com.facebook.buck.multitenant.service

import com.facebook.buck.util.json.ObjectMappers
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.JsonNode
import java.io.InputStream
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Ultimately, we would like to use kotlinx.serialization for this, but we are currently blocked by
 * https://youtrack.jetbrains.com/issue/KT-30998.
 */
fun populateIndexFromStream(index: Index, stream: InputStream): List<String> {
    val parser = ObjectMappers.createParser(stream)
            .enable(JsonParser.Feature.ALLOW_COMMENTS)
            .enable(JsonParser.Feature.ALLOW_TRAILING_COMMA)

    val jsonNode = parser.readValueAsTree<JsonNode>()
    val commits = mutableListOf<String>()
    for (commit in jsonNode.asSequence()) {
        val hash = commit.get("commit").asText()
        val added = toBuildPackages(commit.get("added"))
        val modified = toBuildPackages(commit.get("modified"))
        var removed = toRemovedPackages(commit.get("removed"))
        val changes = Changes(added, modified, removed)
        index.addCommitData(hash, changes)
        commits.add(hash)
    }
    return commits
}

private fun toBuildPackages(node: JsonNode?): List<BuildPackage> {
    if (node == null) {
        return listOf()
    }

    val buildPackages = mutableListOf<BuildPackage>()
    for (buildPackageItem in node) {
        val path = buildPackageItem.get("path").asText()
        val rulesAttr = buildPackageItem.get("rules")
        val rules = mutableSetOf<RawBuildRule>()
        for (ruleEntry in rulesAttr.fields()) {
            val name = ruleEntry.key
            val attributes = ruleEntry.value
            val deps = mutableSetOf<String>()
            val depsAttr = attributes.get("deps")
            if (depsAttr != null) {
                deps.addAll(depsAttr.asSequence().map { it.asText() })
            }
            val buildTarget = "//${path}:${name}"
            rules.add(createRawRule(buildTarget, deps))
        }
        buildPackages.add(BuildPackage(Paths.get(path), rules))
    }

    return buildPackages
}

private fun toRemovedPackages(node: JsonNode?): List<Path> {
    if (node == null) {
        return listOf()
    }

    return node.asSequence().map { Paths.get(it.asText()) }.toList()
}
