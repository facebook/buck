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
package com.facebook.buck.cli;

import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.TargetNodeSpec;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DirtyPrintStreamDecorator;
import com.facebook.buck.util.config.Config;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.function.Function;
import org.immutables.value.Value.Immutable;

@BuckStyleImmutable
@Immutable(copy = false)
public abstract class AbstractProjectGeneratorParameters {

  public abstract CommandRunnerParams getCommandRunnerParams();

  public Console getConsole() {
    return getCommandRunnerParams().getConsole();
  }

  public DirtyPrintStreamDecorator getStdErr() {
    return getCommandRunnerParams().getConsole().getStdErr();
  }

  public DirtyPrintStreamDecorator getStdOut() {
    return getCommandRunnerParams().getConsole().getStdOut();
  }

  public Config getConfig() {
    return getCommandRunnerParams().getBuckConfig().getConfig();
  }

  public Parser getParser() {
    return getCommandRunnerParams().getParser();
  }

  public Path getPath() {
    return getCommandRunnerParams().getCell().getRoot();
  }

  public abstract boolean isDryRun();

  public abstract boolean isWithTests();

  public abstract boolean isWithoutTests();

  public abstract boolean isWithoutDependenciesTests();

  public abstract boolean getEnableParserProfiling();

  public abstract Function<Iterable<String>, ImmutableList<TargetNodeSpec>> getArgsParser();
}
