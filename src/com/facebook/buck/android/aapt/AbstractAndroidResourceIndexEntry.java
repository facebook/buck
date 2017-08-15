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

package com.facebook.buck.android.aapt;
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

import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ComparisonChain;
import java.nio.file.Path;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractAndroidResourceIndexEntry
    implements Comparable<AbstractAndroidResourceIndexEntry> {

  @Value.Parameter
  @JsonProperty
  public abstract RDotTxtEntry.RType getType();

  @Value.Parameter
  @JsonProperty
  public abstract String getName();

  @Value.Parameter
  @JsonProperty
  public abstract Integer getLineNumber();

  @Value.Parameter
  @JsonProperty
  public abstract Integer getColumnNumber();

  @Value.Parameter
  @JsonProperty
  public abstract Path getResourceFilePath();

  @Override
  public int compareTo(AbstractAndroidResourceIndexEntry that) {
    if (this == that) {
      return 0;
    }

    ComparisonChain comparisonChain =
        ComparisonChain.start()
            .compare(this.getType(), that.getType())
            .compare(this.getName(), that.getName());

    return comparisonChain.result();
  }
}
