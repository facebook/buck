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

package com.facebook.buck.rules;

import static com.facebook.buck.io.Watchman.NULL_WATCHMAN;

import com.facebook.buck.android.AndroidDirectoryResolver;
import com.facebook.buck.android.FakeAndroidDirectoryResolver;
import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.config.CellConfig;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.io.Watchman;
import com.facebook.buck.io.WatchmanDiagnosticCache;
import com.facebook.buck.json.ProjectBuildFileParserFactory;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.ProcessExecutor;

import java.io.IOException;

import javax.annotation.Nullable;

import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.MethodInterceptor;

public class TestCellBuilder {

  private ProjectFilesystem filesystem;
  private BuckConfig buckConfig;
  private AndroidDirectoryResolver androidDirectoryResolver;
  @Nullable
  private ProjectBuildFileParserFactory parserFactory;
  private Watchman watchman = NULL_WATCHMAN;
  private CellConfig cellConfig;

  public TestCellBuilder() throws InterruptedException, IOException {
    filesystem = new FakeProjectFilesystem();
    androidDirectoryResolver = new FakeAndroidDirectoryResolver();
    cellConfig = CellConfig.of();
  }

  public TestCellBuilder setFilesystem(ProjectFilesystem filesystem) {
    this.filesystem = filesystem;
    return this;
  }

  public TestCellBuilder setBuckConfig(BuckConfig buckConfig) {
    this.buckConfig = buckConfig;
    return this;
  }

  public TestCellBuilder setAndroidDirectoryResolver(AndroidDirectoryResolver resolver) {
    this.androidDirectoryResolver = resolver;
    return this;
  }

  public TestCellBuilder setBuildFileParserFactory(ProjectBuildFileParserFactory factory) {
    this.parserFactory = factory;
    return this;
  }

  public TestCellBuilder setWatchman(Watchman watchman) {
    this.watchman = watchman;
    return this;
  }

  public TestCellBuilder setCellConfigOverride(CellConfig cellConfig) {
    this.cellConfig = cellConfig;
    return this;
  }

  public Cell build() throws IOException, InterruptedException {
    ProcessExecutor executor = new ProcessExecutor(new TestConsole());

    BuckConfig config = buckConfig == null ?
        FakeBuckConfig.builder().setFilesystem(filesystem).build() :
        buckConfig;

    KnownBuildRuleTypesFactory typesFactory = new KnownBuildRuleTypesFactory(
        executor,
        androidDirectoryResolver);

    if (parserFactory == null) {
      return CellProvider.createForLocalBuild(
          filesystem,
          watchman,
          config,
          cellConfig,
          typesFactory,
          new WatchmanDiagnosticCache()).getCellByPath(filesystem.getRootPath());
    }

    // The constructor for `Cell` is private, and it's in such a central location I don't really
    // want to make it public. Brace yourselves.

    Enhancer enhancer = new Enhancer();
    enhancer.setSuperclass(Cell.class);
    enhancer.setCallback((MethodInterceptor) (obj, method, args, proxy) -> {
      if ("createBuildFileParserFactory".equals(method.getName())) {
        return parserFactory;
      }

      return proxy.invokeSuper(obj, args);
    });

    return (Cell) enhancer.create();
  }

  public static CellPathResolver createCellRoots(
      @Nullable ProjectFilesystem filesystem) {
    ProjectFilesystem toUse = filesystem == null ? new FakeProjectFilesystem() : filesystem;
    return new FakeCellPathResolver(toUse);
  }
}
