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

import com.facebook.eden.thrift.Glob;
import com.facebook.eden.thrift.GlobParams;
import com.facebook.eden.thrift.MountInfo;
import com.facebook.eden.thrift.SHA1Result;
import java.util.List;

public class TestEdenClient implements EdenClient {

  @Override
  public List<SHA1Result> getSHA1(byte[] mountPoint, List<byte[]> paths) {
    throw new RuntimeException("not implemented");
  }

  @Override
  public Glob globFiles(GlobParams params) {
    throw new RuntimeException("not implemented");
  }

  @Override
  public List<MountInfo> listMounts() {
    throw new RuntimeException("not implemented");
  }

  @Override
  public long getPid() {
    throw new RuntimeException("not implemented");
  }
}
