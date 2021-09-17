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

package com.facebook.buck.edenfs;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.skylark.io.Globber;
import com.facebook.buck.skylark.io.GlobberFactory;
import com.facebook.eden.thrift.EdenError;
import java.io.IOException;
import java.util.Collection;
import java.util.Set;

/** Implementation of globber using thrift. */
public class EdenThriftGlobber implements Globber {
  private final EdenMount edenMount;
  private final ForwardRelPath basePath;

  public EdenThriftGlobber(EdenMount edenMount, ForwardRelPath basePath) {
    this.edenMount = edenMount;
    this.basePath = basePath;
  }

  @Override
  public Set<String> run(
      Collection<String> include, Collection<String> exclude, boolean excludeDirectories)
      throws IOException, InterruptedException {
    try {
      return edenMount.globFiles(basePath, include, exclude, excludeDirectories);
    } catch (EdenError e) {
      throw new IOException("Eden error: " + e.message);
    }
  }

  /** Factory for {@link com.facebook.buck.skylark.io.GlobberFactory}. */
  public static class Factory implements GlobberFactory {
    private final EdenMount edenMount;

    public Factory(EdenMount edenMount) {
      this.edenMount = edenMount;
    }

    @Override
    public AbsPath getRoot() {
      return edenMount.getProjectRoot();
    }

    @Override
    public Globber create(ForwardRelPath basePath) {
      return new EdenThriftGlobber(edenMount, basePath);
    }

    @Override
    public void close() {}
  }
}
