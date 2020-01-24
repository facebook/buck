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
import com.facebook.buck.core.parser.buildtargetpattern.UnconfiguredBuildTargetParser
import com.facebook.buck.multitenant.fs.FsAgnosticPath
import com.google.common.collect.ImmutableMap
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class BuildPackageTest {
    @Test fun canSerializeAndDeserializeBuildPackage() {
        val buildPackage = BuildPackage(buildFileDirectory = FsAgnosticPath.of("foo/bar"),
            rules = setOf(RawBuildRule(targetNode = ServiceUnconfiguredTargetNode(
                buildTarget = UnconfiguredBuildTargetParser.parse("cell//foo/bar:baz", true),
                ruleType = RuleType.of("java_library", RuleType.Kind.BUILD),
                attributes = ImmutableMap.of("attr1", "va1", "attr2", "val2")),
                deps = setOf(UnconfiguredBuildTargetParser.parse("cell//foo/bar:baz_lib", true)))),
            errors = listOf(BuildPackageParsingError(message = "parsing error",
                stacktrace = listOf("stack line 1", "stack line 2"))))

        val stream = ByteArrayOutputStream()

        serializePackagesToStream(listOf(buildPackage), stream)

        val deserializedPackages =
            parsePackagesFromStream(ByteArrayInputStream(stream.toByteArray()),
                ::multitenantJsonToBuildPackageParser)

        assertEquals(1, deserializedPackages.size)
        assertEquals(buildPackage, deserializedPackages.first())
    }

    @Test fun canSerializeAndDeserializeBuildPackageWithEmptyPath() {
        val buildPackage = BuildPackage(buildFileDirectory = FsAgnosticPath.of(""), rules = setOf(),
            errors = listOf())

        val stream = ByteArrayOutputStream()

        serializePackagesToStream(listOf(buildPackage), stream)

        val deserializedPackages =
            parsePackagesFromStream(ByteArrayInputStream(stream.toByteArray()),
                ::multitenantJsonToBuildPackageParser)

        assertEquals(1, deserializedPackages.size)
        assertEquals(buildPackage, deserializedPackages.first())
    }
}
