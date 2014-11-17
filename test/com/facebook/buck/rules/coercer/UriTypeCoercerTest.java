/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.rules.coercer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.testutil.FakeProjectFilesystem;

import org.junit.Test;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class UriTypeCoercerTest {

  private ProjectFilesystem filesystem = new FakeProjectFilesystem();
  private Path pathFromRoot = Paths.get("third-party/java");
  private BuildTargetParser buildTargetParser = new BuildTargetParser();

  @Test
  public void canCoerceAValidHttpURI() throws CoerceFailedException, URISyntaxException {
    URI expected = new URI("http://example.org");
    URI uri = new UriTypeCoercer().coerce(
        buildTargetParser,
        filesystem,
        pathFromRoot,
        expected.toString());

    assertEquals(expected, uri);
  }

  @Test
  public void canCoerceAValidHttpsURI() throws CoerceFailedException, URISyntaxException {
    URI expected = new URI("https://example.org");
    URI uri = new UriTypeCoercer().coerce(
        buildTargetParser,
        filesystem,
        pathFromRoot,
        expected.toString());

    assertEquals(expected, uri);
  }

  @Test
  public void canCoerceAMavenURI() throws CoerceFailedException, URISyntaxException {
    URI expected = new URI("mvn:org.hamcrest:hamcrest-core:jar:1.3");
    URI uri = new UriTypeCoercer().coerce(
        buildTargetParser,
        filesystem,
        pathFromRoot,
        expected.toString());

    assertEquals(expected, uri);
  }

  @Test(expected = CoerceFailedException.class)
  public void shouldThrowAMeaningfulExceptionIfURICannotBeCoerced() throws CoerceFailedException {
    new UriTypeCoercer().coerce(
        buildTargetParser,
        filesystem,
        pathFromRoot,
        "not a valid URI");
    fail("Expected coercion failure");
  }
}
