#!/usr/local/bin/thrift -cpp -py

namespace cpp buck.common
namespace java com.facebook.buck.distributed.thrift

# This protocol is under active development and
# will likely be changed in non-compatible ways
struct BuckJob {
  1: string id,
  2: i64 timestamp_ms,
}
