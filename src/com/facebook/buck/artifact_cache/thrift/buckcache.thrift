# Copyright 2016 Facebook. All Rights Reserved.
#
# To refresh the protocol, make sure you have public version of Thrift installed:
#   brew install thrift
# and run the following command:
#   /usr/local/bin/thrift --gen java:generated_annotations=undated  -out src-gen/ src/com/facebook/buck/artifact_cache/thrift/buckcache.thrift
#
# This .thrift file contains the protocol required by the buck client to
# communicate with the buck-cache server.
# This protocol is under active development and
# will likely be changed in non-compatible ways

namespace java com.facebook.buck.artifact_cache.thrift

enum BuckCacheRequestType {
  UNKNOWN = 0,
  FETCH = 100,
  STORE = 101,
  MULTI_FETCH = 102,
  // `DELETE` is a define somewhere inside glibc
  DELETE_REQUEST = 105,
  CONTAINS = 107,
  MANIFEST_APPEND = 108,
  MANIFEST_FETCH = 109,
  MANIFEST_DELETE = 110,
  MANIFEST_SET = 111,
}

struct RuleKey {
  1: optional string hashString;
}

struct ArtifactMetadata {
  1: optional list<RuleKey> ruleKeys;
  2: optional map<string, string> metadata;
  3: optional string buildTarget;
  4: optional string repository;
  5: optional string artifactPayloadCrc32;  // DEPRECATED: Will be removed soon.
  6: optional string scheduleType;
  7: optional string artifactPayloadMd5;
  8: optional bool distributedBuildModeEnabled;
  // Free-form identifier of a service that produced the artifact
  9: optional string producerId;
  // How long it took to build this artifact, in milliseconds
  10: optional i64 buildTimeMs;
  // Hostname of a machine that produced the artifact
  11: optional string producerHostname;
}

enum ContainsResultType {
  CONTAINS = 0,
  DOES_NOT_CONTAIN = 1,
  UNKNOWN_DUE_TO_TRANSIENT_ERRORS = 2,
}

struct ContainsDebugInfo {
  // Fastest store to return a cache hit.
  1: optional string fastestCacheHitStore;
  // The store ID, indicating ZippyDB or Memcached, to return a cache hit.
  2: optional string fastestCacheHitStoreId;
}

struct ContainsResult {
  1: optional ContainsResultType resultType;
  2: optional ContainsDebugInfo debugInfo;
}

struct FetchDebugInfo {
  // All stores used to look up the artifact.
  1: optional list<string> storesLookedUp;

  // 2: DEPRECATED.

  // Fastest store to return a cache hit.
  3: optional string fastestCacheHitStore;
  // The store ID, indicating ZippyDB or Memcached, to return a cache hit.
  4: optional string fastestCacheHitStoreId;
}

struct StoreDebugInfo {
  // All stores used in the write.
  1: optional list<string> storesWrittenInto;
  2: optional i64 artifactSizeBytes;
}

struct BuckCacheStoreRequest {
  1: optional ArtifactMetadata metadata;

  // If this field is not present then the payload is passed via a different
  // out of band method.
  100: optional binary payload;
}

struct BuckCacheStoreResponse {
  1: optional StoreDebugInfo debugInfo;
}

struct BuckCacheFetchRequest {
  1: optional RuleKey ruleKey;
  2: optional string repository;
  3: optional string scheduleType;
  4: optional bool distributedBuildModeEnabled;
}

struct BuckCacheFetchResponse {
  1: optional bool artifactExists;
  2: optional ArtifactMetadata metadata;
  3: optional FetchDebugInfo debugInfo;

  // If this field is not present then the payload is passed via a different
  // out of band method.
  100: optional binary payload;
}

// NOTE: The contains request is only supposed to be best-effort. A CONTAINS
// result only means that it is highly likely that we contain the artifact.
// And a DOES_NOT_CONTAIN result means that it might still be present in stores
// like Memcache, where we do not have a contains check. The third result type
// of UNKNOWN_DUE_TO_TRANSIENT_ERRORS means that some stores returned a MISS,
// while others errored out.
struct BuckCacheMultiContainsRequest {
  1: optional list<RuleKey> ruleKeys;
  2: optional string repository;
  3: optional string scheduleType;
  4: optional bool distributedBuildModeEnabled;
}

struct BuckCacheMultiContainsResponse {
  1: optional list<ContainsResult> results;

  // All stores used to look up the artifact.
  2: optional list<string> storesLookedUp;
}

enum FetchResultType {
  UNKNOWN = 0,
  HIT = 100,
  MISS = 101,
  // CONTAINS indicates that the cache contains an artifact for the key, but
  // could not return it in this request due to resource constraints. The key
  // should be requested again (possibly in a single-key request to ensure
  // resources are available to service the request).
  CONTAINS = 102
  // SKIPPED indicates that, due to resource constraints, no information about
  // the requested key was looked up. The key should be requested again.
  SKIPPED = 103,
  ERROR = 104,
}

struct FetchResult {
  1: optional FetchResultType resultType;
  2: optional ArtifactMetadata metadata;
  3: optional FetchDebugInfo debugInfo;

  // If this field is not present then the payload is passed via a different
  // out of band method.
  100: optional binary payload;
}

struct BuckCacheMultiFetchRequest {
  1: optional list<RuleKey> ruleKeys;
  2: optional string repository;
  3: optional string scheduleType;
  4: optional bool distributedBuildModeEnabled;
}

struct BuckCacheMultiFetchResponse {
  1: optional list<FetchResult> results;
}

struct PayloadInfo {
  1: optional i64 sizeBytes;
}

struct BuckCacheDeleteRequest {
  1: optional list<RuleKey> ruleKeys;
  2: optional string repository;
  3: optional string scheduleType;
  4: optional bool distributedBuildModeEnabled;
}

struct DeleteDebugInfo {
  1: optional list<string> storesDeletedFrom;
}

struct BuckCacheDeleteResponse {
  1: optional DeleteDebugInfo debugInfo;
}

struct Manifest {
  1: optional string key;
  2: optional list<binary> values;
}

struct ManifestAppendRequest {
  1: optional Manifest manifest;
}

struct ManifestAppendResponse {
}

struct ManifestFetchRequest {
  1: optional string manifestKey;
}

struct ManifestFetchResponse {
  1: optional Manifest manifest;
}

struct ManifestDeleteRequest {
  1: optional string manifestKey;
}

struct ManifestDeleteResponse {
}

struct ManifestSetRequest {
  1: optional Manifest manifest;
}

struct ManifestSetResponse {
}

struct BuckCacheRequest {
  1: optional BuckCacheRequestType type = BuckCacheRequestType.UNKNOWN;

  // Can be unset if request is not initiated by buck build,
  // e. g. if buckcache is called by command-line fetch utility.
  2: optional string buckBuildId;

  100: optional list<PayloadInfo> payloads;
  101: optional BuckCacheFetchRequest fetchRequest;
  102: optional BuckCacheStoreRequest storeRequest;
  103: optional BuckCacheMultiFetchRequest multiFetchRequest;
  105: optional BuckCacheDeleteRequest deleteRequest;
  107: optional BuckCacheMultiContainsRequest multiContainsRequest;
  108: optional ManifestAppendRequest manifestAppendRequest;
  109: optional ManifestFetchRequest manifestFetchRequest;
  110: optional ManifestDeleteRequest manifestDeleteRequest;
  111: optional ManifestSetRequest manifestSetRequest;
}

struct BuckCacheResponse {
  1: optional bool wasSuccessful;
  2: optional string errorMessage;

  10: optional BuckCacheRequestType type = BuckCacheRequestType.UNKNOWN;

  // 30: DEPRECATED.

  // Human-readable single-line server info.
  // Can be used only for debugging.
  31: optional string diagnosticServerInfo;

  100: optional list<PayloadInfo> payloads;
  101: optional BuckCacheFetchResponse fetchResponse;
  102: optional BuckCacheStoreResponse storeResponse;
  103: optional BuckCacheMultiFetchResponse multiFetchResponse;
  105: optional BuckCacheDeleteResponse deleteResponse;
  107: optional BuckCacheMultiContainsResponse multiContainsResponse;
  108: optional ManifestAppendResponse manifestAppendResponse;
  109: optional ManifestFetchResponse manifestFetchResponse;
  110: optional ManifestDeleteResponse manifestDeleteResponse;
  111: optional ManifestSetResponse manifestSetResponse;
}
