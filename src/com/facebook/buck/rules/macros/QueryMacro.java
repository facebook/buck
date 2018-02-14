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

package com.facebook.buck.rules.macros;

import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.query.Query;
import com.facebook.buck.versions.TargetNodeTranslator;
import java.util.Optional;

/** Base class for macros that embed a query string. */
public abstract class QueryMacro implements Macro {

  public abstract Query getQuery();

  /** @return a copy of this {@link QueryMacro} with the given {@link Query}. */
  abstract QueryMacro withQuery(Query query);

  @Override
  public final Optional<Macro> translateTargets(
      CellPathResolver cellPathResolver,
      BuildTargetPatternParser<BuildTargetPattern> pattern,
      TargetNodeTranslator translator) {
    return translator.translate(cellPathResolver, pattern, getQuery()).map(this::withQuery);
  }
}
