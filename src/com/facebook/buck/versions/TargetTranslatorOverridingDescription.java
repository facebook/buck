/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.versions;

import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import java.util.Optional;

/**
 * An interface used by descriptions which need to perform custom translations of their constructor
 * args to support versioning.
 */
public interface TargetTranslatorOverridingDescription<A> extends DescriptionWithTargetGraph<A> {

  /** @return the translated constructor arg, if any translation was necessary. */
  Optional<A> translateConstructorArg(
      BuildTarget target,
      CellPathResolver cellNames,
      TargetNodeTranslator translator,
      A constructorArg);
}
