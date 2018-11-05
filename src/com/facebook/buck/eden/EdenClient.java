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

package com.facebook.buck.eden;

import com.facebook.eden.thrift.EdenError;
import com.facebook.eden.thrift.MountInfo;
import com.facebook.eden.thrift.SHA1Result;
import com.facebook.thrift.TException;
import java.io.IOException;
import java.util.List;

/**
 * Client of Eden's fbthrift API that is intended to be a thin wrapper around {@link
 * com.facebook.eden.thrift.EdenService.Client}'s API.
 *
 * <p>Note that implementations of this interface are not guaranteed to be thread-safe.
 */
public interface EdenClient {

  List<SHA1Result> getSHA1(String mountPoint, List<String> paths) throws IOException, TException;

  List<String> getBindMounts(String mountPoint) throws IOException, TException;

  List<MountInfo> listMounts() throws EdenError, IOException, TException;
}
