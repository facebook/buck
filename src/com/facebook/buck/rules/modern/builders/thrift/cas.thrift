namespace java com.facebook.remoteexecution.cas

/*
 * A content digest. A digest for a given binary consists of the size of the
 * binary and its hash. The hash algorithm to use is SHA-256.
 *
 * The size is considered to be an integral part of the digest and cannot be
 * separated. That is, even if the hash field is correctly specified but
 * size_bytes is not, the server MUST reject the request.
 *
 * When a Digest is used to refer to a Thrift struct, it always refers to the
 * message encoded using Protocol.COMPACT.
 */
struct Digest {
  /*
   * The hash. In the case of SHA-256, it will always be a lowercase hex
   * string exactly 64 characters long.
   */
  1: string hash;

  /*
   * The size of the binary, in bytes.
   */
  2: i64 size_bytes;
}

/*
 * A Directory represents a directory node in a file tree, containing zero or
 * more children FileNodes and DirectoryNodes. Each Node contains its name in
 * the directory, the digest of its content (either a file blob or a Directory
 * struct), as well as possibly some metadata about the file or directory.
 *
 * In order to ensure that two equivalent directory trees hash to the same
 * value, the following restrictions MUST be obeyed when constructing a
 * a Directory:
 *   - Every child in the directory must have a path of exactly one segment.
 *     Multiple levels of directory hierarchy may not be collapsed.
 *   - Each child in the directory must have a unique path segment (file name).
 *   - The files and directories in the directory must each be sorted in
 *     lexicographical order by path. The path strings must be sorted by code
 *     point, equivalently, by UTF-8 bytes.
 *
 * A Directory that obeys the restrictions is said to be in canonical form.
 */
struct Directory {
  /*
   * The files in the directory.
   */
  1: list<FileNode> files;

  /*
   * The subdirectories in the directory.
   */
  2: list<DirectoryNode> directories;

  /*
   * The symlinks in the directory.
   */
  3: list<SymlinkNode> symlinks;
}

/*
 * Represents a child of a Directory which is itself a Directory and its
 * associated metadata.
 */
struct DirectoryNode {
  /*
   * The name of the directory.
   */
  1: string name;

  /*
   * The digest of the Directory object represented by this Node.
   */
  2: Digest digest;
}

/*
 * Represents a single file and associated metadata.
 */
struct FileNode {
  /*
   * The name of the file.
   */
  1: string name;

  /*
   * The digest of the file's content.
   */
  2: Digest digest;

  /*
   * True if file is executable, false otherwise.
   */
  3: bool is_executable;
}

/*
 * Represents a symbolic link.
 */
struct SymlinkNode {
  /*
   * The name of the symlink.
   */
  1: string name;

  /*
   * The target path of the symlink. The path separator is a forward slash `/`.
   * The target path can be relative to the parent directory of the symlink or
   * it can be an absolute path starting with `/`. The canonical form forbids
   * the substrings `/./` and `//` in the target
   */
  2: string target;

  /*
   * True if the target is a directory. Required for creating symlinks on
   * Windows.
   */
  3: bool is_directory;
}

/*
 * Request message for ContentAddressableStorage.updateBlob.
 */
struct UpdateBlobRequest {
  /*
   * The digest of the blob. This MUST be the digest of the data field.
   */
  1: Digest content_digest;

  /*
   * The raw binary data.
   */
  2: binary data;
}

/*
 * Response message for ContentAddressableStorage.updateBlob or the response
 * corresponding to a single blob that the client tried to upload using
 * ContentAddressableStorage.batchUpdateBlobs.
 */
struct UpdateBlobResponse {
  /*
   * The digest to which this response corresponds.
   */
  1: Digest blob_digest;
}

/*
 * Request message for ContentAddressableStorage.batchUpdateBlobs.
 */
struct BatchUpdateBlobsRequest {
  /*
   * The individual upload requests.
   */
  1: list<UpdateBlobRequest> requests;
}

/*
 * Response message for ContentAddressableStorage.batchUpdateBlobs.
 */
struct BatchUpdateBlobsResponse {
  /*
   * The responses to the requests.
   */
  1: list<UpdateBlobResponse> responses;
}

/*
 * Request message for ContentAddressableStorage.readBlob.
 */
struct ReadBlobRequest {
  /*
   * The digest to read.
   */
  1: Digest blob_digest;
}

/*
 * Response message for ContentAddressableStorage.readBlob.
 */
struct ReadBlobResponse {
  /*
   * The digest to which this response corresponds.
   */
  1: Digest digest;

  /*
   * The raw binary data.
   */
  2: binary data;
}

/*
 * Request message for ContentAddressableStorage.batchReadBlobs.
 */
struct BatchReadBlobsRequest {
  /*
   * The individual blob requests to read.
   */
  1: list<ReadBlobRequest> requests;
}

/*
 * Response message for ContentAddressableStorage.batchReadBlobs.
 */
struct BatchReadBlobsResponse {
  /*
   * The responses to each read blob request.
   */
  1: list<ReadBlobResponse> responses;
}

/*
 * Request message for ContentAddressableStorage.findMissingBlobs.
 */
struct FindMissingBlobsRequest {
  /*
   * A list of the blob digests to check
   */
  1: list<Digest> blob_digests;
}

/*
 * Reponse message for ContentAddressableStorage.findMissingBlobs.
 */
struct FindMissingBlobsResponse {
  /*
   * A list of the blobs requested *not* present in the storage.
   */
  1: list<Digest> missing_blob_digests,
}

/*
 * Request message for ContentAddressableStorage.getTree.
 */
struct GetTreeRequest {
  /*
   * The digest of the root, which must be an encoded Directory stored in the
   * CAS.
   */
  1: Digest root_digest;

  /*
   * A maximum page size to request. If present, the server will request no
   * more than this many items. Regardless of whether a page size is specified,
   * the server may place its own limit on the number of items to be returned
   * and require the client to retrieve more items using a subsequent request.
   */
  2: optional i32 page_size;

  /*
   * A page token, which must be a value received in a previous getTreeResponse.
   * If present, the server will use it to return the following page of results.
   */
  3: optional string page_token;
}

/*
 * Response message for ContentAddressableStorage.getTree.
 */
struct GetTreeResponse {
  /*
   * The directories descended from the requested root.
   */
  1: list<Directory> directories;

  /*
   * If present, signifies that there are more results which the client can
   * retrieve by passing this as the page_token in a subsequent to
   * getTreeRequest. If empty, signifies that this is the last page of results.
   */
  2: optional string next_page_token;
}

enum ContentAddressableStorageExceptionCode {
  /*
   * Non-specific exception code.
   */
  UNKNOWN = 0;

  /*
   * Provided digest doesn't match blob content.
   */
  DIGEST_INVALID = 1;

  /*
   * Digest requested isn't found.
   */
  DIGEST_NOT_FOUND = 2;
}

/*
 * Represents a CAS failure.
 */
exception ContentAddressableStorageException {
  /*
   * The exception status code.
   */
  1: ContentAddressableStorageExceptionCode code;

  /*
   * A developer-facing error message, which should be in English. Any
   * user-facing error message should be localized and sent in the
   * details field, or localized by the client.
   */
  2: string message;

  /*
   * A list of messages that carry the error details.
   */
  3: list<string> details;
}

/*
 * The CAS (content-addressable storage) is used to store the inputs/outputs
 * from actions and other blobs. Each piece of content is addressed by the
 * digest of its binary data.
 *
 * The lifetime of entries in the CAS is implementation specific, but it SHOULD
 * be long enough to allow for newly-added and recently looked-up entries to be
 * used in subsequent calls.
 */
service ContentAddressableStorage {
  /*
   * Upload a single blob.
   */
  UpdateBlobResponse updateBlob(
    1: UpdateBlobRequest request,
  ) throws (
    1: ContentAddressableStorageException ex,
  );

  /*
   * Upload many blobs at once.
   */
  BatchUpdateBlobsResponse batchUpdateBlobs(
    1: BatchUpdateBlobsRequest request,
  ) throws (
    1: ContentAddressableStorageException ex,
  )

  /*
   * Retrieve the contents of a blob.
   */
  ReadBlobResponse readBlob(
    1: ReadBlobRequest request,
  ) throws (
    1: ContentAddressableStorageException ex,
  )

  /*
   * Download many blobs at once.
   */
  BatchReadBlobsResponse batchReadBlobs(
    1: BatchReadBlobsRequest request,
  ) throws (
    1: ContentAddressableStorageException ex,
  )

  /*
   * Determines if blobs are present in the CAS. Clients can use this API before
   * uploading blobs to determine which ones are already present in the CAS and
   * do not need to be uploaded again.
   */
  FindMissingBlobsResponse findMissingBlobs(
    1: FindMissingBlobsRequest request,
  ) throws (
    1: ContentAddressableStorageException ex,
  )

  /*
   * Fetch the entire directory tree rooted at a node.
   *
   * This request must be targeted at a Directory stored in the CAS. The
   * server will enumerate the Directory tree recursively and return every
   * node descended from the root. The exact traversal order is unspecified
   * and is not guaranteed to be stable across multiple invocations.
   *
   * If part of the tree is missing from the CAS, the server will return the
   * portion present and omit the rest.
   */
  GetTreeResponse getTree(
    1: GetTreeRequest request,
  ) throws (
    1: ContentAddressableStorageException ex,
  )
}
