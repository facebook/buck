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

import com.facebook.buck.multitenant.fs.FsAgnosticPath
import io.mockk.every
import io.mockk.slot
import io.mockk.spyk
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildPackageParserTest {
    @Test fun canParseOutputOfBuck() {

        val parser = spyk(BuckShellBuildPackageParser(Paths.get("does_not_matter")))

        val patternsSlot = slot<Path>()
        val outputFile = slot<Path>()

        // Mock private function that calls buck with just writing a json to provided file
        every { parser["execBuck"](capture(patternsSlot), capture(outputFile)) } answers {
            outputFile.captured.toFile().writeText(getData())
        }

        val packages =
            parser.parsePackages(listOf(FsAgnosticPath.of("foo"), FsAgnosticPath.of("foo/bar")))

        assertEquals(2, packages.size)
        assertTrue(packages.any { p -> p.buildFileDirectory == FsAgnosticPath.of("foo") })
        assertTrue(packages.any { p -> p.buildFileDirectory == FsAgnosticPath.of("foo/bar") })
    }

    @Test fun canParseUniverse() {
        val parser = spyk(BuckShellBuildPackageParser(Paths.get("does_not_matter")))

        val patternsSlot = slot<Path>()
        val outputFile = slot<Path>()

        // Mock private function that calls buck with just writing a json to provided file
        every { parser["execBuck"](capture(patternsSlot), capture(outputFile)) } answers {
            assertEquals("//..." + System.lineSeparator(), patternsSlot.captured.toFile().readText())
            outputFile.captured.toFile().writeText(getData())
        }
        val packages = parser.parseUniverse()
        assertEquals(2, packages.size)
        assertTrue(packages.any { p -> p.buildFileDirectory == FsAgnosticPath.of("foo") })
        assertTrue(packages.any { p -> p.buildFileDirectory == FsAgnosticPath.of("foo/bar") })
    }

    private fun getData() = """
[{
  "path" : "foo",
  "nodes" : {
    "target1" : {
      "buildTarget" : "//foo:target1",
      "ruleType" : {
        "name" : "java_binary",
        "kind" : "BUILD"
      },
      "attributes" : {
        "buck.base_path" : "foo",
        "buck.type" : "java_binary",
        "name" : "target1",
        "srcs" : [ "a.java" ],
        "deps" : [ "//foo/bar:target2" ]
      },
      "visibilityPatterns" : [ ],
      "withinViewPatterns" : [ ],
      "deps" : [ "//foo/bar:target2" ]
    }
  }
  },
  {
  "path" : "foo/bar",
  "nodes" : {
    "target1" : {
      "buildTarget" : "//foo/bar:target2",
      "ruleType" : {
        "name" : "java_binary",
        "kind" : "BUILD"
      },
      "attributes" : {
        "buck.base_path" : "foo/bar",
        "buck.type" : "java_library",
        "name" : "target2",
        "srcs" : [ "b.java" ],
        "deps" : [ ]
      },
      "visibilityPatterns" : [ ],
      "withinViewPatterns" : [ ],
      "deps" : [ ]
    }
  }
  }
]""".trimIndent()
}
