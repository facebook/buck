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

package com.facebook.buck.thrift;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.python.PythonLibraryDescription;
import com.facebook.buck.python.PythonTestUtils;
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.testutil.AllExistingProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.util.Optional;

public class ThriftLibraryBuilder extends AbstractNodeBuilder<
    ThriftConstructorArg,
    ThriftLibraryDescription,
    ThriftLibrary> {

  private ThriftLibraryBuilder(
      ThriftLibraryDescription description,
      BuildTarget target) {
    super(description, target);
  }

  public static ThriftLibraryBuilder from(BuildTarget target) {
    ThriftBuckConfig thriftBuckConfig =
        new ThriftBuckConfig(
            FakeBuckConfig.builder()
                .setSections(
                    ImmutableMap.of(
                        "thrift",
                        ImmutableMap.of("compiler", "/usr/bin/thrift")))
                .setFilesystem(new AllExistingProjectFilesystem())
                .build());
    PythonLibraryDescription pythonLibraryDescription =
        new PythonLibraryDescription(PythonTestUtils.PYTHON_PLATFORMS);
    return new ThriftLibraryBuilder(
        new ThriftLibraryDescription(
            thriftBuckConfig,
            ImmutableList.of(
                new ThriftPythonEnhancer(
                    thriftBuckConfig,
                    ThriftPythonEnhancer.Type.ASYNCIO,
                    pythonLibraryDescription),
                new ThriftPythonEnhancer(
                    thriftBuckConfig,
                    ThriftPythonEnhancer.Type.NORMAL,
                    pythonLibraryDescription),
                new ThriftPythonEnhancer(
                    thriftBuckConfig,
                    ThriftPythonEnhancer.Type.TWISTED,
                    pythonLibraryDescription))),
        target);
  }

  public ThriftLibraryBuilder setPyOptions(ImmutableSet<String> pyOptions) {
    arg.pyOptions = pyOptions;
    return this;
  }

  public ThriftLibraryBuilder setPyBaseModule(String pyBaseModule) {
    arg.pyBaseModule = Optional.of(pyBaseModule);
    return this;
  }

  public ThriftLibraryBuilder setPyAsyncioBaseModule(String pyAsyncioBaseModule) {
    arg.pyAsyncioBaseModule = Optional.of(pyAsyncioBaseModule);
    return this;
  }

  public ThriftLibraryBuilder setPyTwistedBaseModule(String pyTwistedBaseModule) {
    arg.pyTwistedBaseModule = Optional.of(pyTwistedBaseModule);
    return this;
  }

  public ThriftLibraryBuilder setSrcs(ImmutableMap<SourcePath, ImmutableList<String>> srcs) {
    arg.srcs = srcs;
    return this;
  }

}
