/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.httpserver;

import com.google.common.collect.ImmutableSet;
import com.google.template.soy.data.SoyMapData;

import org.eclipse.jetty.server.Request;

import java.io.IOException;

/**
 * Handles requests to the root URI, {@code /}.
 */
class IndexHandlerDelegate extends AbstractTemplateHandlerDelegate {

  IndexHandlerDelegate() {
    super(ImmutableSet.of("index.soy"));
  }

  @Override
  public String getTemplateForRequest(Request baseRequest) {
    return "buck.index";
  }

  @Override
  public SoyMapData getDataForRequest(Request baseRequest) throws IOException {
    return new SoyMapData();
  }
}
