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

import com.facebook.eden.thrift.EdenError;
import com.facebook.eden.thrift.EdenService;
import com.facebook.eden.thrift.MountInfo;
import com.facebook.eden.thrift.SHA1Result;
import com.facebook.thrift.TException;
import java.io.IOException;
import java.util.List;

/** Default client which is a wrapper for thrift client interface. */
public class EdenClientImpl implements EdenClient {
  private final EdenService.Client client;

  public EdenClientImpl(EdenService.Client client) {
    this.client = client;
  }

  @Override
  public List<SHA1Result> getSHA1(byte[] mountPoint, List<byte[]> paths)
      throws IOException, TException, EdenError {
    return client.getSHA1(mountPoint, paths);
  }

  @Override
  public List<MountInfo> listMounts() throws EdenError, IOException, TException {
    return client.listMounts();
  }

  @Override
  public long getPid() throws EdenError, IOException, TException {
    return client.getPid();
  }
}
