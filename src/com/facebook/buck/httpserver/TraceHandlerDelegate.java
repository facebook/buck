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

package com.facebook.buck.httpserver;

import com.facebook.buck.util.trace.BuildTraces;
import com.facebook.buck.util.trace.BuildTraces.TraceAttributes;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Resources;
import java.io.IOException;
import java.net.URL;
import java.util.regex.Matcher;
import javax.annotation.Nullable;
import org.eclipse.jetty.server.Request;

/** HTTP handler for requests to the {@code /trace} path. */
public class TraceHandlerDelegate implements TemplateHandlerDelegate {

  private final BuildTraces buildTraces;

  TraceHandlerDelegate(BuildTraces buildTraces) {
    this.buildTraces = buildTraces;
  }

  @Override
  public String getTemplateForRequest(Request baseRequest) {
    return "trace";
  }

  @Override
  @Nullable
  public ImmutableMap<String, Object> getDataForRequest(Request baseRequest) throws IOException {
    String path = baseRequest.getPathInfo();
    Matcher matcher = TraceDataHandler.ID_PATTERN.matcher(path);
    if (!matcher.matches()) {
      return null;
    }

    ImmutableMap.Builder<String, Object> templateData = ImmutableMap.builder();

    String id = matcher.group(1);
    templateData.put("traceId", id);

    // Read the args to `buck` out of the Chrome Trace.
    TraceAttributes traceAttributes = buildTraces.getTraceAttributesFor(id);
    templateData.put("dateTime", traceAttributes.getFormattedDateTime());
    if (traceAttributes.getCommand().isPresent()) {
      templateData.put("command", traceAttributes.getCommand().get());
    }

    return templateData.build();
  }

  @Override
  public URL getTemplateGroup() {
    return Resources.getResource(TraceHandlerDelegate.class, "templates.stg");
  }
}
