/*
 * Copyright 2015-present Facebook, Inc.
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

import static com.facebook.buck.util.environment.Platform.WINDOWS;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.file.downloader.Downloader;
import com.facebook.buck.file.downloader.impl.ExplodingDownloader;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TestBuildRuleParams;
import com.facebook.buck.rules.TestBuildRuleResolver;
import com.facebook.buck.rules.TestCellPathResolver;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import javax.annotation.Nullable;
import org.easymock.EasyMock;
import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.junit.Rule;
import org.junit.Test;

public class RemoteFileTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void ensureOutputIsAddedToBuildableContextSoItIsCached() {
    Downloader downloader = new ExplodingDownloader();
    BuildTarget target = BuildTargetFactory.newInstance("//cheese:cake");
    BuildRuleResolver resolver = new TestBuildRuleResolver();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));
    RemoteFile remoteFile =
        new RemoteFileBuilder(downloader, target)
            .setUrl("http://www.facebook.com/")
            .setSha1(Hashing.sha1().hashLong(42))
            .build(resolver);

    BuildableContext buildableContext = EasyMock.createNiceMock(BuildableContext.class);
    buildableContext.recordArtifact(
        pathResolver.getRelativePath(remoteFile.getSourcePathToOutput()));
    EasyMock.replay(buildableContext);

    remoteFile.getBuildSteps(
        FakeBuildContext.withSourcePathResolver(pathResolver), buildableContext);

    EasyMock.verify(buildableContext);
  }

  @Test
  public void shouldSaveToFinalLocationAfterSha1IsVerified() throws Exception {
    String value = "I like cake";
    HashCode hashCode = Hashing.sha1().hashBytes(value.getBytes(UTF_8));
    Path output = runTheMagic(null, value, hashCode);

    assertThat(output, exists());
  }

  @Test
  public void shouldNotSaveToFinalLocationUntilAfterSha1IsVerified() throws Exception {
    Path output = runTheMagic(null, "eat more cheese", Hashing.sha1().hashLong(42));

    assertThat(output, not(exists()));
  }

  @Test
  public void shouldNotSaveFileToFinalLocationIfTheDownloadFails() throws Exception {
    String value = "I also like cake";
    HashCode hashCode = Hashing.sha1().hashBytes(value.getBytes(UTF_8));
    Path output = runTheMagic(new ExplodingDownloader(), value, hashCode);

    assertThat(output, not(exists()));
  }

  @Test
  public void shouldNotMakeDownloadedFileExecutableWhenTypeIsData() throws Exception {
    assumeThat(Platform.detect(), is(not(WINDOWS)));
    String value = "I like cake";
    HashCode hashCode = Hashing.sha1().hashBytes(value.getBytes(UTF_8));
    Path output = runTheMagic(null, value, hashCode, RemoteFile.Type.DATA);

    assertThat(output, exists());
    assertThat(output, not(isExecutable()));
  }

  @Test
  public void shouldMakeDownloadedFileExecutableIfRequested() throws Exception {
    assumeThat(Platform.detect(), is(not(WINDOWS)));
    String value = "I like cake";
    HashCode hashCode = Hashing.sha1().hashBytes(value.getBytes(UTF_8));
    Path output = runTheMagic(null, value, hashCode, RemoteFile.Type.EXECUTABLE);

    assertThat(output, exists());
    assertThat(output, isExecutable());
  }

  @Test
  public void shouldUnzipExplodedArchive() throws Exception {
    // zip archive of a directory called hello, which contains hello.txt file with "hello\n" content
    byte[] archiveContent = {
      0x50,
      0x4B,
      0x03,
      0x04,
      0x0A,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      (byte) 0xED,
      0x5B,
      0x26,
      0x4A,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x06,
      0x00,
      0x1C,
      0x00,
      0x68,
      0x65,
      0x6C,
      0x6C,
      0x6F,
      0x2F,
      0x55,
      0x54,
      0x09,
      0x00,
      0x03,
      (byte) 0x8D,
      (byte) 0xF0,
      0x6F,
      0x58,
      (byte) 0x94,
      (byte) 0xF0,
      0x6F,
      0x58,
      0x75,
      0x78,
      0x0B,
      0x00,
      0x01,
      0x04,
      (byte) 0xAD,
      (byte) 0x9E,
      (byte) 0xC2,
      0x5D,
      0x04,
      0x00,
      0x00,
      0x00,
      0x00,
      0x50,
      0x4B,
      0x03,
      0x04,
      0x0A,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x15,
      0x59,
      0x26,
      0x4A,
      0x20,
      0x30,
      0x3A,
      0x36,
      0x06,
      0x00,
      0x00,
      0x00,
      0x06,
      0x00,
      0x00,
      0x00,
      0x0F,
      0x00,
      0x1C,
      0x00,
      0x68,
      0x65,
      0x6C,
      0x6C,
      0x6F,
      0x2F,
      0x68,
      0x65,
      0x6C,
      0x6C,
      0x6F,
      0x2E,
      0x74,
      0x78,
      0x74,
      0x55,
      0x54,
      0x09,
      0x00,
      0x03,
      0x3A,
      (byte) 0xEB,
      0x6F,
      0x58,
      (byte) 0x94,
      (byte) 0xF0,
      0x6F,
      0x58,
      0x75,
      0x78,
      0x0B,
      0x00,
      0x01,
      0x04,
      (byte) 0xAD,
      (byte) 0x9E,
      (byte) 0xC2,
      0x5D,
      0x04,
      0x00,
      0x00,
      0x00,
      0x00,
      0x68,
      0x65,
      0x6C,
      0x6C,
      0x6F,
      0x0A,
      0x50,
      0x4B,
      0x01,
      0x02,
      0x1E,
      0x03,
      0x0A,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      (byte) 0xED,
      0x5B,
      0x26,
      0x4A,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x06,
      0x00,
      0x18,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x10,
      0x00,
      (byte) 0xED,
      0x41,
      0x00,
      0x00,
      0x00,
      0x00,
      0x68,
      0x65,
      0x6C,
      0x6C,
      0x6F,
      0x2F,
      0x55,
      0x54,
      0x05,
      0x00,
      0x03,
      (byte) 0x8D,
      (byte) 0xF0,
      0x6F,
      0x58,
      0x75,
      0x78,
      0x0B,
      0x00,
      0x01,
      0x04,
      (byte) 0xAD,
      (byte) 0x9E,
      (byte) 0xC2,
      0x5D,
      0x04,
      0x00,
      0x00,
      0x00,
      0x00,
      0x50,
      0x4B,
      0x01,
      0x02,
      0x1E,
      0x03,
      0x0A,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x15,
      0x59,
      0x26,
      0x4A,
      0x20,
      0x30,
      0x3A,
      0x36,
      0x06,
      0x00,
      0x00,
      0x00,
      0x06,
      0x00,
      0x00,
      0x00,
      0x0F,
      0x00,
      0x18,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x01,
      0x00,
      0x00,
      0x00,
      (byte) 0xA4,
      (byte) 0x81,
      0x40,
      0x00,
      0x00,
      0x00,
      0x68,
      0x65,
      0x6C,
      0x6C,
      0x6F,
      0x2F,
      0x68,
      0x65,
      0x6C,
      0x6C,
      0x6F,
      0x2E,
      0x74,
      0x78,
      0x74,
      0x55,
      0x54,
      0x05,
      0x00,
      0x03,
      0x3A,
      (byte) 0xEB,
      0x6F,
      0x58,
      0x75,
      0x78,
      0x0B,
      0x00,
      0x01,
      0x04,
      (byte) 0xAD,
      (byte) 0x9E,
      (byte) 0xC2,
      0x5D,
      0x04,
      0x00,
      0x00,
      0x00,
      0x00,
      0x50,
      0x4B,
      0x05,
      0x06,
      0x00,
      0x00,
      0x00,
      0x00,
      0x02,
      0x00,
      0x02,
      0x00,
      (byte) 0xA1,
      0x00,
      0x00,
      0x00,
      (byte) 0x8F,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
    };
    HashCode hashCode = Hashing.sha1().hashBytes(archiveContent);
    Path output = runTheMagic(archiveContent, hashCode, RemoteFile.Type.EXPLODED_ZIP);

    assertThat(output, exists());
    Path filePath = output.resolve("hello").resolve("hello.txt");
    assertThat(filePath, exists());
    assertThat(filePath, hasContent("hello\n".getBytes(UTF_8)));
  }

  private static Matcher<Path> exists() {
    return new BaseMatcher<Path>() {
      @Override
      public boolean matches(Object o) {
        return Files.exists((Path) o);
      }

      @Override
      public void describeTo(Description description) {
        description.appendText("File must exist");
      }
    };
  }

  private static Matcher<Path> isExecutable() {
    return new BaseMatcher<Path>() {
      @Override
      public boolean matches(Object o) {
        return Files.isExecutable((Path) o);
      }

      @Override
      public void describeTo(Description description) {
        description.appendText("File must be executable");
      }
    };
  }

  private static Matcher<Path> hasContent(byte[] content) {
    return new BaseMatcher<Path>() {
      @Override
      public boolean matches(Object o) {
        try {
          return Arrays.equals(Files.readAllBytes((Path) o), content);
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }

      @Override
      public void describeTo(Description description) {
        description.appendText("File has unexpected content");
      }
    };
  }

  private Path runTheMagic(
      @Nullable Downloader downloader, String contentsOfFile, HashCode hashCode) throws Exception {
    return runTheMagic(downloader, contentsOfFile, hashCode, RemoteFile.Type.DATA);
  }

  private Path runTheMagic(
      @Nullable Downloader downloader,
      String contentsOfFile,
      HashCode hashCode,
      RemoteFile.Type type)
      throws Exception {
    return runTheMagic(downloader, contentsOfFile.getBytes(UTF_8), hashCode, type);
  }

  private Path runTheMagic(byte[] contentsOfFile, HashCode hashCode, RemoteFile.Type type)
      throws Exception {

    Downloader downloader =
        (eventBus, uri, output) -> {
          Files.createDirectories(output.getParent());
          Files.write(output, contentsOfFile);
          return true;
        };
    return runTheMagic(downloader, contentsOfFile, hashCode, type);
  }

  private Path runTheMagic(
      @Nullable Downloader downloader,
      byte[] contentsOfFile,
      HashCode hashCode,
      RemoteFile.Type type)
      throws Exception {

    if (downloader == null) {
      downloader =
          (eventBus, uri, output) -> {
            Files.createDirectories(output.getParent());
            Files.write(output, contentsOfFile);
            return true;
          };
    }

    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(tmp.getRoot().toAbsolutePath());

    BuildTarget target = BuildTargetFactory.newInstance("//cake:walk");
    BuildRuleParams params = TestBuildRuleParams.create();
    RemoteFile remoteFile =
        new RemoteFile(
            target,
            filesystem,
            params,
            downloader,
            new URI("http://example.com"),
            hashCode,
            "output.txt",
            type);
    BuildRuleResolver resolver = new TestBuildRuleResolver();
    resolver.addToIndex(remoteFile);
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));

    ImmutableList<Step> buildSteps =
        remoteFile.getBuildSteps(
            FakeBuildContext.withSourcePathResolver(pathResolver), new FakeBuildableContext());
    ExecutionContext context =
        TestExecutionContext.newBuilder()
            .setCellPathResolver(TestCellPathResolver.get(filesystem))
            .build();
    for (Step buildStep : buildSteps) {
      int result;
      try {
        result = buildStep.execute(context).getExitCode();
      } catch (HumanReadableException e) {
        result = -1;
      }
      if (result != 0) {
        break;
      }
    }

    return pathResolver.getAbsolutePath(remoteFile.getSourcePathToOutput());
  }
}
