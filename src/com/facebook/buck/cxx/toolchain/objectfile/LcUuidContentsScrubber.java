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

package com.facebook.buck.cxx.toolchain.objectfile;

import com.facebook.buck.io.file.FileContentsScrubber;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.Range;
import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class LcUuidContentsScrubber implements FileContentsScrubber {

  private static final HashFunction HASH_FUNCTION = Hashing.murmur3_128();

  private static final int UUID_LENGTH = 16;
  private static final byte[] ZERO_UUID = new byte[UUID_LENGTH];

  private static final long NUMBER_OF_HASH_RANGES = 64;

  private static final long CONCURRENT_FILE_SIZE_THRESHOLD = (50 * 1024 * 1024);
  private final boolean scrubConcurrently;

  public LcUuidContentsScrubber(boolean scrubConcurrently) {
    this.scrubConcurrently = scrubConcurrently;
  }

  @Override
  public void scrubFile(FileChannel file) throws IOException, ScrubException {
    if (!Machos.isMacho(file)) {
      return;
    }

    long size = file.size();
    MappedByteBuffer map = file.map(FileChannel.MapMode.READ_WRITE, 0, size);

    resetUuidIfPresent(map);
    HashCode hashCode = computeHash(map, size);

    map.rewind();
    try {
      Machos.setUuidIfPresent(map, Arrays.copyOf(hashCode.asBytes(), UUID_LENGTH));
    } catch (Machos.MachoException e) {
      throw new ScrubException(e.getMessage());
    }
  }

  private HashCode computeHash(MappedByteBuffer map, long fileSize) {
    List<Range<Long>> ranges = generateHashRanges(fileSize);
    List<Pair<Range<Long>, ByteBuffer>> rangeBuffers =
        ranges.stream()
            .map(
                (range) -> {
                  map.position(range.lowerEndpoint().intValue());
                  map.limit(range.upperEndpoint().intValue());
                  ByteBuffer subBuffer = map.slice().asReadOnlyBuffer();
                  return new Pair<>(range, subBuffer);
                })
            .collect(Collectors.toList());

    ByteBuffer concatenatedHashes = ByteBuffer.allocate((HASH_FUNCTION.bits() / 8) * ranges.size());
    Stream<Pair<Range<Long>, ByteBuffer>> rangeBuffersStream =
        (scrubConcurrently && fileSize >= CONCURRENT_FILE_SIZE_THRESHOLD)
            ? rangeBuffers.parallelStream()
            : rangeBuffers.stream();
    rangeBuffersStream
        .map((pair) -> new Pair<>(pair.getFirst(), hashByteBuffer(pair.getSecond())))
        // NB: Sorting is only required for the parallel stream. Since we're sorting a small number
        // of tiny elements, we will sort even in the sequential stream case (rather than introduce
        // more branching).
        .sorted(Comparator.comparing(pair -> pair.getFirst().lowerEndpoint()))
        .forEachOrdered((pair) -> concatenatedHashes.put(pair.getSecond().asBytes()));

    concatenatedHashes.rewind();
    return hashByteBuffer(concatenatedHashes);
  }

  private static HashCode hashByteBuffer(ByteBuffer byteBuffer) {
    Hasher hasher = HASH_FUNCTION.newHasher();
    hasher.putBytes(byteBuffer);
    return hasher.hash();
  }

  private static List<Range<Long>> generateHashRanges(long fileSize) {
    long uniformRangeSize = fileSize / NUMBER_OF_HASH_RANGES;
    long finalRangeSize = uniformRangeSize + fileSize % NUMBER_OF_HASH_RANGES;

    ArrayList<Range<Long>> orderedRanges = new ArrayList<>();
    for (int i = 0; i < NUMBER_OF_HASH_RANGES; ++i) {
      final boolean isLastRange = (i + 1) == NUMBER_OF_HASH_RANGES;
      final long startOffset = i * uniformRangeSize;
      final long end = startOffset + (isLastRange ? finalRangeSize : uniformRangeSize);
      orderedRanges.add(Range.closedOpen(startOffset, end));
    }

    return orderedRanges;
  }

  /** Sets the LC_UUID to all zeroes if it's part of the Mach-O file. */
  protected static void resetUuidIfPresent(MappedByteBuffer map) throws ScrubException {
    try {
      Machos.setUuidIfPresent(map, ZERO_UUID);
    } catch (Machos.MachoException e) {
      throw new ScrubException(e.getMessage());
    }
  }
}
