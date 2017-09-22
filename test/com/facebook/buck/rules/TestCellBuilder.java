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
import com.facebook.buck.io.filesystem.impl.DefaultProjectFilesystemFactory;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import java.io.IOException;
import javax.annotation.Nullable;

public class TestCellBuilder {

  private ProjectFilesystem filesystem;
  private BuckConfig buckConfig;
  private AndroidDirectoryResolver androidDirectoryResolver;
  private Watchman watchman = NULL_WATCHMAN;
  private CellConfig cellConfig;
  private KnownBuildRuleTypesFactory knownBuildRuleTypesFactory;
  private SdkEnvironment sdkEnvironment;

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

  public TestCellBuilder setWatchman(Watchman watchman) {
    this.watchman = watchman;
    return this;
  }

  public TestCellBuilder setCellConfigOverride(CellConfig cellConfig) {
    this.cellConfig = cellConfig;
    return this;
  }

  public TestCellBuilder setKnownBuildRuleTypesFactory(
      KnownBuildRuleTypesFactory knownBuildRuleTypesFactory) {
    this.knownBuildRuleTypesFactory = knownBuildRuleTypesFactory;
    return this;
  }

  public TestCellBuilder setSdkEnvironment(SdkEnvironment sdkEnvironment) {
    this.sdkEnvironment = sdkEnvironment;
    return this;
  }

  public Cell build() throws IOException, InterruptedException {
    ProcessExecutor executor = new DefaultProcessExecutor(new TestConsole());

    BuckConfig config =
        buckConfig == null
            ? FakeBuckConfig.builder().setFilesystem(filesystem).build()
            : buckConfig;

    SdkEnvironment sdkEnvironment =
        this.sdkEnvironment == null
            ? SdkEnvironment.create(config, executor, androidDirectoryResolver)
            : this.sdkEnvironment;

    KnownBuildRuleTypesFactory typesFactory =
        knownBuildRuleTypesFactory == null
            ? new KnownBuildRuleTypesFactory(executor, androidDirectoryResolver, sdkEnvironment)
            : knownBuildRuleTypesFactory;

    return CellProvider.createForLocalBuild(
            filesystem,
            watchman,
            config,
            cellConfig,
            typesFactory,
            sdkEnvironment,
            new DefaultProjectFilesystemFactory())
        .getCellByPath(filesystem.getRootPath());
  }

  public static CellPathResolver createCellRoots(@Nullable ProjectFilesystem filesystem) {
    ProjectFilesystem toUse = filesystem == null ? new FakeProjectFilesystem() : filesystem;
    return TestCellPathResolver.get(toUse);
  }
}
