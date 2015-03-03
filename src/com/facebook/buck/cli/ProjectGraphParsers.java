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

package com.facebook.buck.cli;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.parser.TargetNodeSpec;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.HumanReadableException;

import com.google.common.collect.ImmutableMap;

import java.io.IOException;

/**
 * Utilities for creating {@link ProjectGraphParser} instances.
 */
public class ProjectGraphParsers {
  // Utility class, do not instantiate.
  private ProjectGraphParsers() { }

  /**
   * Creates a {@link ProjectGraphParser} which calls into a
   * concrete {@link Parser} object to create {@link TargetGraph}s
   * for a build project.
   */
  public static ProjectGraphParser createProjectGraphParser(
      final Parser parser,
      final ParserConfig parserConfig,
      final BuckEventBus buckEventBus,
      final Console console,
      final ImmutableMap<String, String> environment,
      final boolean enableProfiling
  ) throws IOException, InterruptedException {
    return new ProjectGraphParser() {
      @Override
      public TargetGraph buildTargetGraphForTargetNodeSpecs(
          Iterable<? extends TargetNodeSpec> targetNodeSpecs)
        throws IOException, InterruptedException {
        try {
          return parser.buildTargetGraphForTargetNodeSpecs(
              targetNodeSpecs,
              parserConfig,
              buckEventBus,
              console,
              environment,
              enableProfiling);
        } catch (BuildTargetException | BuildFileParseException e) {
          throw new HumanReadableException(e);
        }
      }
    };
  }
}
