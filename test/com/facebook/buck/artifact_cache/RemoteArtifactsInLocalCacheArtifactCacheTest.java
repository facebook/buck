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

package com.facebook.buck.artifact_cache;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.BorrowablePath;
import com.facebook.buck.io.LazyPath;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.Test;

public class RemoteArtifactsInLocalCacheArtifactCacheTest {

  @Test
  public void testStoresOnlyToRemote() throws Exception {
    Fixture f = new Fixture();
    RuleKey ruleKey = f.getRuleKey();

    ListenableFuture<Void> store =
        f.getCacheUnderTest()
            .store(
                ArtifactInfo.builder().addRuleKeys(ruleKey).build(),
                BorrowablePath.notBorrowablePath(f.getArtifactPath()));
    store.get();

    LazyPath output = f.getResultPath();
    assertFalse(f.getLocalCache().fetch(ruleKey, output).getType().isSuccess());
    assertTrue(f.getRemoteCache().fetch(ruleKey, output).getType().isSuccess());
  }

  @Test
  public void testReadsFromRemoteAndStoresToLocal() throws Exception {
    Fixture f = new Fixture();
    RuleKey ruleKey = f.getRuleKey();

    f.getRemoteCache().store(ArtifactInfo.builder().addRuleKeys(ruleKey).build(), new byte[0]);

    assertFalse(f.getLocalCache().fetch(ruleKey, f.getResultPath()).getType().isSuccess());
    assertTrue(f.getCacheUnderTest().fetch(ruleKey, f.getResultPath()).getType().isSuccess());
    assertTrue(f.getLocalCache().fetch(ruleKey, f.getResultPath()).getType().isSuccess());
  }

  @Test
  public void testReadsFromLocal() throws Exception {
    Fixture f = new Fixture();
    RuleKey ruleKey = f.getRuleKey();

    f.getLocalCache().store(ArtifactInfo.builder().addRuleKeys(ruleKey).build(), new byte[0]);

    assertTrue(f.getCacheUnderTest().fetch(ruleKey, f.getResultPath()).getType().isSuccess());
  }

  @Test
  public void testHandlesMiss() throws Exception {
    Fixture f = new Fixture();
    RuleKey ruleKey = f.getRuleKey();

    assertFalse(f.getCacheUnderTest().fetch(ruleKey, f.getResultPath()).getType().isSuccess());
  }

  private class Fixture {
    private Path artifact;
    private Path result;
    private RuleKey ruleKey;
    private InMemoryArtifactCache localCache;
    private InMemoryArtifactCache remoteCache;
    private ArtifactCache cacheUnderTest;

    public Fixture() throws InterruptedException, IOException {
      ProjectFilesystem tmp = FakeProjectFilesystem.createJavaOnlyFilesystem();
      artifact = tmp.createNewFile(tmp.resolve("artifact"));
      result = tmp.createNewFile(tmp.resolve("result"));
      tmp.writeContentsToPath("data", artifact);

      ruleKey = new RuleKey("001122");

      localCache = new InMemoryArtifactCache();
      remoteCache = new InMemoryArtifactCache();
      cacheUnderTest = new RemoteArtifactsInLocalCacheArtifactCache(localCache, remoteCache);
    }

    public Path getArtifactPath() {
      return artifact;
    }

    public LazyPath getResultPath() {
      return LazyPath.ofInstance(result);
    }

    public RuleKey getRuleKey() {
      return ruleKey;
    }

    public InMemoryArtifactCache getLocalCache() {
      return localCache;
    }

    public InMemoryArtifactCache getRemoteCache() {
      return remoteCache;
    }

    public ArtifactCache getCacheUnderTest() {
      return cacheUnderTest;
    }
  }
}
