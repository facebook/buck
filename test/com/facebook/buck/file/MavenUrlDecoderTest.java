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

package com.facebook.buck.file;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;

import org.junit.Test;

import java.net.URI;
import java.net.URISyntaxException;

public class MavenUrlDecoderTest {

  @Test
  public void parseMvnUrlWithDefaultDomain() throws URISyntaxException {
    URI seen = MavenUrlDecoder.toHttpUrl(
        ImmutableMap.of("mvn", "http://foo.bar"),
        new URI("mvn:org.seleniumhq.selenium:selenium-java:jar:2.42.2"));

    URI expected = new URI(
        "http://foo.bar/org/seleniumhq/selenium/selenium-java/2.42.2/selenium-java-2.42.2.jar");

    assertEquals(expected, seen);
  }

  @Test
  public void parseMvnUrlWithMultipleDomains() throws URISyntaxException {
    URI seen = MavenUrlDecoder.toHttpUrl(
        ImmutableMap.of(
            "mvn", "http://foo.bar",
            "jcentral", "http://bar.raz"),
        new URI("jcentral:org.seleniumhq.selenium:selenium-java:jar:2.42.2"));

    URI expected = new URI(
        "http://bar.raz/org/seleniumhq/selenium/selenium-java/2.42.2/selenium-java-2.42.2.jar");

    assertEquals(expected, seen);
  }

  @Test
  public void parseMvnUrlWithDefaultDomainAndAarType() throws URISyntaxException {
    URI seen = MavenUrlDecoder.toHttpUrl(
        ImmutableMap.of("mvn", "http://foo.bar"),
        new URI("mvn:org.jdeferred:jdeferred-android-aar:aar:1.2.4"));

    URI expected = new URI(
        "http://foo.bar/org/jdeferred/jdeferred-android-aar/1.2.4/jdeferred-android-aar-1.2.4.aar");

    assertEquals(expected, seen);
  }

  @Test
  public void parseMvnUrlWithCustomDomain() throws URISyntaxException {
    URI seen = MavenUrlDecoder.toHttpUrl(
        ImmutableMap.of("mvn", "http://foo.bar"),
        new URI("mvn:http://custom.org/:org.seleniumhq.selenium:selenium-java:jar:2.42.2"));

    URI expected = new URI(
        "http://custom.org/org/seleniumhq/selenium/selenium-java/2.42.2/selenium-java-2.42.2.jar");

    assertEquals(expected, seen);
  }

  @Test
  @SuppressWarnings("PMD.EmptyCatchBlock")
  public void optionalServerUrlMustBeHttpOrHttps() throws URISyntaxException {
    ImmutableMap<String, String> repos = ImmutableMap.of("mvn", "http://foo.bar");
    MavenUrlDecoder.toHttpUrl(
        repos,
        new URI("mvn:http://example.org/:org.seleniumhq.selenium:selenium-java:jar:2.42.2"));
    MavenUrlDecoder.toHttpUrl(
        repos,
        new URI("mvn:https://example.org/:org.seleniumhq.selenium:selenium-java:jar:2.42.2"));
    try {
      MavenUrlDecoder.toHttpUrl(
          repos,
          new URI("mvn:mvn://custom.org/:org.seleniumhq.selenium:selenium-java:jar:2.42.2"));
      fail();
    } catch (HumanReadableException expected) {
      // Ignored
    }
  }

  @Test
  public void shouldAddSlashesToMavenRepoUriIfOneIsMissing() throws URISyntaxException {
    String validUri = "mvn:junit:junit:jar:4.12";
    URI slashless = MavenUrlDecoder.toHttpUrl(
        ImmutableMap.of("mvn", "http://www.example.com"),
        new URI(validUri));
    URI withslash = MavenUrlDecoder.toHttpUrl(
        ImmutableMap.of("mvn", "http://www.example.com"),
        new URI(validUri));

    assertEquals(withslash, slashless);
  }

  @Test(expected = IllegalArgumentException.class)
  public void shouldRefuseToParseNonMavenUri() throws URISyntaxException {
    MavenUrlDecoder.toHttpUrl(ImmutableMap.<String, String>of(),
        new URI("http://www.example.com/"));
  }
}
