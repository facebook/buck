/*
 * Copyright 2018-present Facebook, Inc.
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

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TestBuildRuleCreationContextFactory;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.SingleThreadedActionGraphBuilder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.unarchive.ArchiveFormat;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class HttpArchiveDescriptionTest {

  public @Rule TemporaryPaths temporaryDir = new TemporaryPaths();
  public @Rule ExpectedException thrown = ExpectedException.none();

  private HttpArchiveDescription description;
  private SingleThreadedActionGraphBuilder graphBuilder;
  private ProjectFilesystem filesystem;
  private TargetGraph targetGraph;

  @Before
  public void setUp() {
    description = new HttpArchiveDescription();
    graphBuilder = new TestActionGraphBuilder();
    filesystem = TestProjectFilesystems.createProjectFilesystem(temporaryDir.getRoot());
    targetGraph = TargetGraph.EMPTY;
  }

  private HttpArchive createDescrptionFromArgs(String targetName, HttpArchiveDescriptionArg args) {
    BuildTarget target = BuildTargetFactory.newInstance(targetName);
    return (HttpArchive)
        description.createBuildRule(
            TestBuildRuleCreationContextFactory.create(targetGraph, graphBuilder, filesystem),
            target,
            new BuildRuleParams(
                ImmutableSortedSet::of, ImmutableSortedSet::of, ImmutableSortedSet.of()),
            args);
  }

  private Path getOutputPath(HttpArchive buildRule) {
    graphBuilder.computeIfAbsent(buildRule.getBuildTarget(), t -> buildRule);
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    return pathResolver.getAbsolutePath(buildRule.getSourcePathToOutput());
  }

  @Test
  public void usesRuleNameIfOutNotProvided() {
    HttpArchive buildRule =
        createDescrptionFromArgs(
            "//foo/bar:baz",
            HttpArchiveDescriptionArg.builder()
                .setName("baz")
                .setSha256("2c7ae82268c1bab8d048a76405a6f7f39c2d95791df37ad2c36cb9252ee3a6ca")
                .setUrls(ImmutableList.of(URI.create("https://example.com/first.zip")))
                .build());

    Assert.assertEquals(
        filesystem.resolve(
            filesystem.getBuckPaths().getGenDir().resolve(Paths.get("foo", "bar", "baz", "baz"))),
        getOutputPath(buildRule));
  }

  @Test
  public void usesOutIfProvided() {
    HttpArchive buildRule =
        createDescrptionFromArgs(
            "//foo/bar:baz",
            HttpArchiveDescriptionArg.builder()
                .setName("baz")
                .setSha256("2c7ae82268c1bab8d048a76405a6f7f39c2d95791df37ad2c36cb9252ee3a6ca")
                .setUrls(ImmutableList.of(URI.create("https://example.com/first.zip")))
                .setOut("my_cool_exe")
                .build());

    Assert.assertEquals(
        filesystem.resolve(
            filesystem
                .getBuckPaths()
                .getGenDir()
                .resolve(Paths.get("foo", "bar", "baz", "my_cool_exe"))),
        getOutputPath(buildRule));
  }

  @Test
  public void givesAUsableErrorIfShaCouldNotBeParsed() {
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage("when parsing sha256 of //foo/bar:baz");
    createDescrptionFromArgs(
        "//foo/bar:baz",
        HttpArchiveDescriptionArg.builder()
            .setName("baz")
            .setSha256("z")
            .setUrls(ImmutableList.of(URI.create("https://example.com/first.zip")))
            .build());
  }

  @Test
  public void givesAUsableErrorIfLooksLikeASha1() {
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "does not appear to be a sha256 hash. Expected 256 bits, got 160 bits when parsing //foo/bar:baz");
    createDescrptionFromArgs(
        "//foo/bar:baz",
        HttpArchiveDescriptionArg.builder()
            .setName("baz")
            .setSha256("37a575feb201ecd7591cbe1558747a2b4d9b9562")
            .setUrls(ImmutableList.of(URI.create("https://example.com/first.zip")))
            .build());
  }

  @Test
  public void givesAUsableErrorIfZeroUrlsProvided() {
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage("At least one url must be provided for //foo/bar:baz");
    createDescrptionFromArgs(
        "//foo/bar:baz",
        HttpArchiveDescriptionArg.builder()
            .setName("baz")
            .setSha256("2c7ae82268c1bab8d048a76405a6f7f39c2d95791df37ad2c36cb9252ee3a6ca")
            .setUrls(ImmutableList.of())
            .build());
  }

  @Test
  public void givesAUsableErrorIfNonHttpOrHttpsUrlIsProvided() {
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "Unsupported protocol 'ftp' for url ftp://ftp.example.com/second.zip in //foo/bar:baz. Must be http or https");
    createDescrptionFromArgs(
        "//foo/bar:baz",
        HttpArchiveDescriptionArg.builder()
            .setName("baz")
            .setSha256("2c7ae82268c1bab8d048a76405a6f7f39c2d95791df37ad2c36cb9252ee3a6ca")
            .setUrls(
                ImmutableList.of(
                    URI.create("https://example.com/first.zip"),
                    URI.create("ftp://ftp.example.com/second.zip")))
            .build());
  }

  @Test
  public void givesAUsableErrorIfNoTypeCouldBeGuessedFromUrlAndNoTypeIsProvided() {
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "Could not determine file type from urls of //foo/bar:baz. One url must end "
            + "with one of .tar, .tar.bz2, .tar.gz, .tar.xz, .tar.zst, .zip, or type must be set "
            + "to one of tar, tar.bz2, tar.gz, tar.xz, tar.zst, zip");
    createDescrptionFromArgs(
        "//foo/bar:baz",
        HttpArchiveDescriptionArg.builder()
            .setName("baz")
            .setSha256("2c7ae82268c1bab8d048a76405a6f7f39c2d95791df37ad2c36cb9252ee3a6ca")
            .setUrls(
                ImmutableList.of(
                    URI.create("https://example.com/first.invalid.ext"),
                    URI.create("https://ftp.example.com/second.zip.invalid.ext")))
            .build());
  }

  @Test
  public void givesAUsableErrorIfInvalidTypeIsProvided() {
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "invalid.ext is not a valid type of archive for //foo/bar:baz. type must be one of "
            + "tar, tar.bz2, tar.gz, tar.xz, tar.zst, zip");
    createDescrptionFromArgs(
        "//foo/bar:baz",
        HttpArchiveDescriptionArg.builder()
            .setName("baz")
            .setSha256("2c7ae82268c1bab8d048a76405a6f7f39c2d95791df37ad2c36cb9252ee3a6ca")
            .setUrls(
                ImmutableList.of(
                    URI.create("https://example.com/first.zip"),
                    URI.create("https://ftp.example.com/second.zip")))
            .setType("invalid.ext")
            .build());
  }

  @Test
  public void usesZipArchiveTypeInsteadOfUrl() {
    usesProvidedFormatInsteadOfGuessedOne(ArchiveFormat.ZIP, "zip", ".tar");
  }

  @Test
  public void usesTarArchiveTypeInsteadOfUrl() {
    usesProvidedFormatInsteadOfGuessedOne(ArchiveFormat.TAR, "tar", ".zip");
  }

  @Test
  public void usesTarGzArchiveTypeInsteadOfUrl() {
    usesProvidedFormatInsteadOfGuessedOne(ArchiveFormat.TAR_GZ, "tar.gz", ".zip");
  }

  @Test
  public void usesTarBz2ArchiveTypeInsteadOfUrl() {
    usesProvidedFormatInsteadOfGuessedOne(ArchiveFormat.TAR_BZ2, "tar.bz2", ".zip");
  }

  @Test
  public void usesTarXzArchiveTypeInsteadOfUrl() {
    usesProvidedFormatInsteadOfGuessedOne(ArchiveFormat.TAR_XZ, "tar.xz", ".zip");
  }

  void usesProvidedFormatInsteadOfGuessedOne(
      ArchiveFormat expectedFormat, String type, String suffix) {
    HttpArchive buildRule =
        createDescrptionFromArgs(
            "//foo/bar:baz",
            HttpArchiveDescriptionArg.builder()
                .setName("baz")
                .setSha256("2c7ae82268c1bab8d048a76405a6f7f39c2d95791df37ad2c36cb9252ee3a6ca")
                .setUrls(ImmutableList.of(URI.create("https://example.com/first" + suffix)))
                .setType(type)
                .build());

    Assert.assertEquals(expectedFormat, buildRule.getFormat());
  }

  @Test
  public void guessZipArchiveTypeFromUrl() {
    usesFileNameToGuessArchiveType(ArchiveFormat.ZIP, ".zip");
  }

  @Test
  public void guessTarArchiveTypeFromUrl() {
    usesFileNameToGuessArchiveType(ArchiveFormat.TAR, ".tar");
  }

  @Test
  public void guessTarGzArchiveTypeFromUrl() {
    usesFileNameToGuessArchiveType(ArchiveFormat.TAR_GZ, ".tar.gz");
  }

  @Test
  public void guessTarBz2ArchiveTypeFromUrl() {
    usesFileNameToGuessArchiveType(ArchiveFormat.TAR_BZ2, ".tar.bz2");
  }

  @Test
  public void guessTarXzArchiveTypeFromUrl() {
    usesFileNameToGuessArchiveType(ArchiveFormat.TAR_XZ, ".tar.xz");
  }

  private void usesFileNameToGuessArchiveType(ArchiveFormat expectedFormat, String suffix) {
    HttpArchive buildRule =
        createDescrptionFromArgs(
            "//foo/bar:baz",
            HttpArchiveDescriptionArg.builder()
                .setName("baz")
                .setSha256("2c7ae82268c1bab8d048a76405a6f7f39c2d95791df37ad2c36cb9252ee3a6ca")
                .setUrls(ImmutableList.of(URI.create("https://example.com/first" + suffix)))
                .build());

    Assert.assertEquals(expectedFormat, buildRule.getFormat());
  }

  @Test
  public void setsUpDependencyOnImplicitHttpFileRule() {
    HttpArchive buildRule =
        createDescrptionFromArgs(
            "//foo/bar:baz",
            HttpArchiveDescriptionArg.builder()
                .setName("baz")
                .setSha256("2c7ae82268c1bab8d048a76405a6f7f39c2d95791df37ad2c36cb9252ee3a6ca")
                .setUrls(ImmutableList.of(URI.create("https://example.com/first.tar.gz")))
                .build());

    HttpFile dependency = (HttpFile) buildRule.getBuildDeps().first();
    Assert.assertEquals(
        "//foo/bar:baz#archive-download", dependency.getBuildTarget().getFullyQualifiedName());
  }
}
