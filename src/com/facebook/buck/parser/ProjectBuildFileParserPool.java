/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.parser;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.api.FileParser;
import com.facebook.buck.parser.api.ProjectBuildFileParser;
import com.facebook.buck.parser.api.Syntax;
import com.facebook.buck.parser.config.ParserConfig;
import com.facebook.buck.util.concurrent.ResourcePool;
import java.io.IOException;

/** Parser pool for {@link BuildFileManifest}s. */
class ProjectBuildFileParserPool extends FileParserPool<BuildFileManifest> {
  private static final Logger LOG = Logger.get(ProjectBuildFileParserPool.class);

  private final boolean enableProfiler;

  /** @param maxParsersPerCell maximum number of parsers to create for a single cell. */
  public ProjectBuildFileParserPool(
      int maxParsersPerCell,
      ProjectBuildFileParserFactory projectBuildFileParserFactory,
      boolean enableProfiler) {
    super(maxParsersPerCell, projectBuildFileParserFactory);
    this.enableProfiler = enableProfiler;
  }

  @Override
  void reportProfile() {
    if (!enableProfiler) {
      return;
    }
    synchronized (this) {
      for (ResourcePool<FileParser<BuildFileManifest>> resourcePool :
          parserResourcePools.values()) {
        resourcePool.callOnEachResource(
            parser -> {
              try {
                ((ProjectBuildFileParser) parser).reportProfile();
              } catch (IOException exception) {
                LOG.debug(
                    exception, "Exception raised during reportProfile() and we're ignoring it");
              }
            });
      }
    }
  }

  /**
   * This is a temporary work-around for not creating many instances of the Skylark parser, since
   * they are completely unnecessary and do not improve performance. This does not really belong
   * here and may potentially be made finer grained (per file).
   *
   * <p>TODO(buckteam): remove once Python DSL parser is gone.
   */
  @Override
  boolean shouldUsePoolForCell(Cell cell) {
    ParserConfig parserConfig = cell.getBuckConfig().getView(ParserConfig.class);
    // the only interpreter that benefits from pooling is Python DSL parser, which is used only in
    // polyglot mode or if default syntax is set to Python DSL
    return parserConfig.isPolyglotParsingEnabled()
        || parserConfig.getDefaultBuildFileSyntax() == Syntax.PYTHON_DSL;
  }
}
