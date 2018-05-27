/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.skylark.function;

import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.Assert.assertThat;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.skylark.SkylarkFilesystem;
import com.facebook.buck.skylark.io.impl.NativeGlobber;
import com.facebook.buck.skylark.packages.PackageContext;
import com.facebook.buck.skylark.parser.context.ParseContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.events.PrintingEventHandler;
import com.google.devtools.build.lib.packages.BazelLibrary;
import com.google.devtools.build.lib.syntax.BuildFileAST;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.Mutability;
import com.google.devtools.build.lib.syntax.ParserInputSource;
import com.google.devtools.build.lib.syntax.Runtime;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import java.util.EnumSet;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class ReadConfigTest {

  private Path root;
  private PrintingEventHandler eventHandler;
  private ImmutableMap<String, ImmutableMap<String, String>> rawConfig;

  @Before
  public void setUp() throws InterruptedException {
    ProjectFilesystem projectFilesystem = FakeProjectFilesystem.createRealTempFilesystem();
    SkylarkFilesystem fileSystem = SkylarkFilesystem.using(projectFilesystem);
    root = fileSystem.getPath(projectFilesystem.getRootPath().toString());
    eventHandler = new PrintingEventHandler(EnumSet.allOf(EventKind.class));
    rawConfig = ImmutableMap.of();
  }

  @Test
  public void defaultValueIsReturned() throws Exception {
    assertThat(
        evaluate("value = read_config('foo', 'bar', 'baz')").lookup("value"), equalTo("baz"));
  }

  @Test
  public void noneIsReturnedWhenFieldIsNotPresent() throws Exception {
    assertThat(
        evaluate("value = read_config('foo', 'bar')").lookup("value"), equalTo(Runtime.NONE));
  }

  @Test
  public void configValueIsReturnedIfExists() throws Exception {
    rawConfig = ImmutableMap.of("foo", ImmutableMap.of("bar", "value"));
    assertThat(evaluate("value = read_config('foo', 'bar')").lookup("value"), equalTo("value"));
  }

  private Environment evaluate(String expression) throws IOException, InterruptedException {
    Path buildFile = root.getChild("BUCK");
    FileSystemUtils.writeContentAsLatin1(buildFile, expression);
    return evaluate(buildFile);
  }

  private Environment evaluate(Path buildFile) throws IOException, InterruptedException {
    try (Mutability mutability = Mutability.create("BUCK")) {
      return evaluate(buildFile, mutability);
    }
  }

  private Environment evaluate(Path buildFile, Mutability mutability)
      throws IOException, InterruptedException {
    byte[] buildFileContent =
        FileSystemUtils.readWithKnownFileSize(buildFile, buildFile.getFileSize());
    BuildFileAST buildFileAst =
        BuildFileAST.parseBuildFile(
            ParserInputSource.create(buildFileContent, buildFile.asFragment()), eventHandler);
    Environment env =
        Environment.builder(mutability)
            .setGlobals(BazelLibrary.GLOBALS)
            .useDefaultSemantics()
            .build();
    ParseContext parseContext =
        new ParseContext(
            PackageContext.builder()
                .setGlobber(NativeGlobber.create(root))
                .setRawConfig(rawConfig)
                .setPackageIdentifier(
                    PackageIdentifier.create(RepositoryName.DEFAULT, PathFragment.create("pkg")))
                .setEventHandler(eventHandler)
                .build());
    parseContext.setup(env);
    env.setup("read_config", ReadConfig.create());
    boolean exec = buildFileAst.exec(env, eventHandler);
    if (!exec) {
      Assert.fail("Build file evaluation must have succeeded");
    }
    return env;
  }
}
