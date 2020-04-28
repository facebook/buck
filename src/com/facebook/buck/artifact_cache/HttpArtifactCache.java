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

package com.facebook.buck.artifact_cache;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.io.file.LazyPath;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import javax.annotation.Nullable;

public final class HttpArtifactCache extends AbstractNetworkCache {

  public HttpArtifactCache(NetworkCacheArgs args) {
    super(args);
  }

  @Override
  protected FetchResult fetchImpl(@Nullable BuildTarget target, RuleKey ruleKey, LazyPath output)
      throws IOException {
    throw new RuntimeException("HttpArtifactCache is deprecated and unimplemented");
  }

  @Override
  protected MultiContainsResult multiContainsImpl(ImmutableSet<RuleKey> ruleKeys) {
    throw new UnsupportedOperationException("multiContains is not supported");
  }

  @Override
  protected StoreResult storeImpl(ArtifactInfo info, Path file) throws IOException {
    throw new RuntimeException("HttpArtifactCache is deprecated and unimplemented");
  }

  @Override
  protected CacheDeleteResult deleteImpl(List<RuleKey> ruleKeys) {
    throw new RuntimeException("Delete operation is not yet supported");
  }

  @Override
  protected MultiFetchResult multiFetchImpl(
      Iterable<AbstractAsynchronousCache.FetchRequest> requests) {
    throw new RuntimeException("multiFetch not supported");
  }
}
