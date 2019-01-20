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

package com.facebook.buck.file.downloader.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import com.facebook.buck.core.exceptions.HumanReadableException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Test;

public class MavenUrlDecoderTest {

  @Test
  public void parseMvnUrlWithDefaultDomain() throws URISyntaxException {
    URI seen =
        MavenUrlDecoder.toHttpUrl(
            Optional.of("http://foo.bar"),
            new URI("mvn:org.seleniumhq.selenium:selenium-java:jar:2.42.2"));

    URI expected =
        new URI(
            "http://foo.bar/org/seleniumhq/selenium/selenium-java/2.42.2/selenium-java-2.42.2.jar");

    assertEquals(expected, seen);
  }

  @Test
  public void parseMvnUrlWithDefaultDomainAndAarType() throws URISyntaxException {
    URI seen =
        MavenUrlDecoder.toHttpUrl(
            Optional.of("http://foo.bar"),
            new URI("mvn:org.jdeferred:jdeferred-android-aar:aar:1.2.4"));

    URI expected =
        new URI(
            "http://foo.bar/org/jdeferred/jdeferred-android-aar/1.2.4/jdeferred-android-aar-1.2.4.aar");

    assertEquals(expected, seen);
  }

  @Test
  public void parseMvnUrlWithDefaultDomainAndTarGzType() throws URISyntaxException {
    URI seen =
        MavenUrlDecoder.toHttpUrl(
            Optional.of("http://foo.bar"),
            new URI("mvn:org.apache.karaf:apache-karaf:tar.gz:3.0.8"));

    URI expected =
        new URI("http://foo.bar/org/apache/karaf/apache-karaf/3.0.8/apache-karaf-3.0.8.tar.gz");

    assertEquals(expected, seen);
  }

  @Test
  public void parseMvnUrlWithDefaultDomainAndZipType() throws URISyntaxException {
    URI seen =
        MavenUrlDecoder.toHttpUrl(
            Optional.of("http://foo.bar"), new URI("mvn:org.apache.karaf:apache-karaf:zip:3.0.8"));

    URI expected =
        new URI("http://foo.bar/org/apache/karaf/apache-karaf/3.0.8/apache-karaf-3.0.8.zip");

    assertEquals(expected, seen);
  }

  @Test
  public void parseMvnUrlWithDefaultDomainAndExeType() throws URISyntaxException {
    URI seen =
        MavenUrlDecoder.toHttpUrl(
            Optional.of("http://foo.bar"), new URI("mvn:org.apache.karaf:apache-karaf:exe:3.0.8"));

    URI expected =
        new URI("http://foo.bar/org/apache/karaf/apache-karaf/3.0.8/apache-karaf-3.0.8.exe");

    assertEquals(expected, seen);
  }

  @Test
  public void parseMvnUrlWithDefaultDomainAndPexType() throws URISyntaxException {
    URI seen =
        MavenUrlDecoder.toHttpUrl(
            Optional.of("http://foo.bar"), new URI("mvn:org.apache.karaf:apache-karaf:pex:3.0.8"));

    URI expected =
        new URI("http://foo.bar/org/apache/karaf/apache-karaf/3.0.8/apache-karaf-3.0.8.pex");

    assertEquals(expected, seen);
  }

  @Test
  public void parseMvnUrlWithCustomDomain() throws URISyntaxException {
    URI seen =
        MavenUrlDecoder.toHttpUrl(
            Optional.of("http://foo.bar"),
            new URI("mvn:http://custom.org/:org.seleniumhq.selenium:selenium-java:jar:2.42.2"));

    URI expected =
        new URI(
            "http://custom.org/org/seleniumhq/selenium/selenium-java/2.42.2/selenium-java-2.42.2.jar");

    assertEquals(expected, seen);
  }

  @Test
  @SuppressWarnings("PMD.EmptyCatchBlock")
  public void optionalServerUrlMustBeHttpOrHttps() throws URISyntaxException {
    Optional<String> repo = Optional.of("http://foo.bar");
    MavenUrlDecoder.toHttpUrl(
        repo, new URI("mvn:http://example.org/:org.seleniumhq.selenium:selenium-java:jar:2.42.2"));
    MavenUrlDecoder.toHttpUrl(
        repo, new URI("mvn:https://example.org/:org.seleniumhq.selenium:selenium-java:jar:2.42.2"));
    try {
      MavenUrlDecoder.toHttpUrl(
          repo, new URI("mvn:mvn://custom.org/:org.seleniumhq.selenium:selenium-java:jar:2.42.2"));
      fail();
    } catch (HumanReadableException expected) {
      // Ignored
    }
  }

  @Test
  public void optionalServerUrlIsOptional() throws URISyntaxException {
    Optional<String> repo = Optional.of("http://foo.bar");
    URI uri =
        MavenUrlDecoder.toHttpUrl(
            repo, new URI("mvn:org.seleniumhq.selenium:selenium-java:jar:2.42.2"));
    assertThat(uri.getHost(), Matchers.equalTo("foo.bar"));
  }

  @Test
  public void shouldAddSlashesToMavenRepoUriIfOneIsMissing() throws URISyntaxException {
    String validUri = "mvn:junit:junit:jar:4.12";
    URI slashless =
        MavenUrlDecoder.toHttpUrl(Optional.of("http://www.example.com"), new URI(validUri));
    URI withslash =
        MavenUrlDecoder.toHttpUrl(Optional.of("http://www.example.com/"), new URI(validUri));

    assertEquals(withslash, slashless);
  }

  @Test
  public void shouldUseClassifierToConstructUrl() throws URISyntaxException {
    assertEquals(
        new URI(
            "https://repo1.maven.org/maven2/org/codehaus/groovy/groovy-groovysh/2.4.1/groovy-groovysh-2.4.1-indy.jar"),
        MavenUrlDecoder.toHttpUrl(
            Optional.of("https://repo1.maven.org/maven2"),
            new URI("mvn:org.codehaus.groovy:groovy-groovysh:jar:indy:2.4.1")));
  }

  @Test
  public void shouldUseClassifierWithHostToConstructUrl() throws URISyntaxException {
    assertEquals(
        new URI(
            "http://foo.com/org/codehaus/groovy/groovy-groovysh/2.4.1/groovy-groovysh-2.4.1-indy.jar"),
        MavenUrlDecoder.toHttpUrl(
            Optional.of("https://repo1.maven.org/maven2"),
            new URI("mvn:http://foo.com:org.codehaus.groovy:groovy-groovysh:jar:indy:2.4.1")));

    assertEquals(
        new URI(
            "https://foo.com/org/codehaus/groovy/groovy-groovysh/2.4.1/groovy-groovysh-2.4.1-indy.jar"),
        MavenUrlDecoder.toHttpUrl(
            Optional.of("https://repo1.maven.org/maven2"),
            new URI("mvn:https://foo.com:org.codehaus.groovy:groovy-groovysh:jar:indy:2.4.1")));
  }

  @Test(expected = HumanReadableException.class)
  public void shouldNotAcceptWrongHostSchema() throws URISyntaxException {
    MavenUrlDecoder.toHttpUrl(
        Optional.of("https://repo1.maven.org/maven2"),
        new URI("mvn:ftp://foo.com:org.codehaus.groovy:groovy-groovysh:jar:indy:2.4.1"));
  }

  @Test(expected = IllegalArgumentException.class)
  public void shouldRefuseToParseNonMavenUri() throws URISyntaxException {
    MavenUrlDecoder.toHttpUrl(Optional.empty(), new URI("http://www.example.com/"));
  }
}
