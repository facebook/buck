include "cas.thrift"

namespace java com.facebook.remoteexecution.executionengine

/*
 * A single property for the environment. If an unknown `name` is
 * provided in the requirements for an Action, the server SHOULD
 * reject the execution request. If permitted by the server, the same `name`
 * may occur multiple times.
 *
 * The server is also responsible for specifying the interpretation of
 * property `value`s. For instance, a property describing how much RAM must be
 * available may be interpreted as allowing a worker with 16GB to fulfill a
 * request for 8GB, while a property describing the OS environment on which
 * the action must be performed may require an exact match with the worker's
 * OS.
 *
 * The server MAY use the `value` of one or more properties to determine how
 * it sets up the execution environment, such as by making specific system
 * files available to the worker.
 */
struct Property {
  /*
   * The property name.
   */
  1: string name;

  /*
   * The property value.
   */
  2: string value;
}

/*
 * A Platform is a set of requirements, such as hardware, operating system, or
 * compiler toolchain, for an Action's execution environment. A Platform is
 * represented as a series of key-value pairs representing the properties that
 * are required of the platform.
 */
struct Platform {
  /*
   * The properties that make up this platform. In order to ensure that
   * equivalent Platforms always hash to the same value, the properties MUST
   * be lexicographically sorted by name, and then by value. Sorting of strings
   * is done by code point, equivalently, by the UTF-8 bytes.
   */
  1: list<Property> properties;
}

/*
 * A variable to be set in the running program's environment.
 */
struct EnvironmentVariable {
  /*
   * The variable name.
   */
  1: string name;

  /*
   * The variable value.
   */
  2: string value;
}

/*
 * The actual Command executed by a worker running an Action. The environment
 * (such as which system libraries or binaries are available, and what
 * filesystems are mounted where) is defined by and specific to the
 * implementation of the worker.
 */
struct Command {
  /*
   * The arguments to the command. The first argument must be the path to
   * the executable, which must be either a relative path, in which case it
   * is evaluated with respect to the input root, or an absolute path.
   */
  1: list<string> arguments;

  /*
   * The environment variables to set when running the program. The worker may
   * provide its own default environment variables; these defaults can be
   * overridden using this field. Additional variables can also be specified.
   *
   * In order to ensure that equivalent Commands always hash to the same value,
   * the environment variables MUST be lexicographically sorted by name. Sorting
   * of strings is done by code point or, equivalently, by the UTF-8 bytes.
   */
  2: list<EnvironmentVariable> environment_variables;

  /*
   * A list of the output files that the client expects to retrieve from the
   * Action. Only listed files, as well as directories listed in the
   * output_directories field, will be returned to the client as output. Other
   * files are discarded.
   *
   * Paths are relative to the working directory of the action execution.
   * They are specified using a single forward slash (`/`) as a path
   * separator, even if the execution platform natively uses a different
   * separator. The path MUST NOT include a trailing slash, nor a leading slash,
   * being a relative path.
   *
   * In order to ensure consistent hashing of the same Action, the output paths
   * MUST be sorted lexicographically by code point (or, equivalently, by UTF-8
   * bytes).
   *
   * An output file cannot be duplicated, be a parent of another output file, be
   * a child of a listed output directory, or have the same path as any of the
   * listed output directories.
   */
  3: list<string> output_files;

  /*
   * A list of the output directories that the client expects to retrieve from
   * the Action. Only the contents of the indicated directories (recursively
   * including the contents of their subdirectories) will be returned, as well
   * as files listed in the output_files field. Other files will be discarded.
   *
   * Paths are relative to the working directory of the action execution.
   * They are specified using a single forward slash (`/`) as a path
   * separator, even if the execution platform natively uses a different
   * separator. The path MUST NOT include a trailing slash, nor a leading slash,
   * being a relative path.
   *
   * The special value of empty string is allowed, although not recommended, and
   * can be used to capture the entire working directory tree, including inputs.
   *
   * In order to ensure consistent hashing of the same Action, the output paths
   * MUST be sorted lexicographically by code point (or, equivalently, by UTF-8
   * bytes).
   *
   * An output directory cannot be duplicated, be a parent of another output
   * directory, be a parent of a listed output file, or have the same path as
   * any of the listed output files.
   */
  4: list<string> output_directories;

  /*
   * The platform requirements for the execution environment. The server MAY
   * choose to execute the action on any worker satisfying the requirements, so
   * the client SHOULD ensure that running the action on any such worker will
   * have the same result.
   */
  5: Platform platform;

  /*
   * The working directory, relative to the input root, for the command to run
   * in. It must be a directory which exists in the input tree. If it is left
   * empty, then the action is run in the input root.
   */
  6: string working_directory;
}

/*
 * An Action captures all the information about an execution which is required
 * to reproduce it.
 *
 * Actions are the core component of the Remote Execution system. A single
 * Action represents a repeatable action that can be performed by the
 * execution service. Actions can be succinctly identified by the digest of
 * their wire format encoding and, once an Action has been executed, will be
 * cached in the action cache. Future requests can then use the cached result
 * rather than needing to run afresh.
 *
 * When a server completes execution of an Action, it MAY choose to cache the
 * ActionResult in the ActionCache unless `do_not_cache` is `true`. Clients
 * SHOULD expect the server to do so. By default, future calls to execute
 * the same Action will also serve their results from the cache. Clients must
 * take care to understand the caching behaviour. Ideally, all Actions will be
 * reproducible so that serving a result from cache is always desirable and
 * correct.
 */
struct Action {
  /*
   * The digest of the Command to run. MUST be present in the CAS.
   */
  1: cas.Digest command_digest;

  /*
   * The digest of the root Directory for the input files. Files in the
   * directory tree will be available in the correct location on the remote
   * worker before the command is executed. The root directory, as well as
   * the blobs referred to, MUST be in the CAS.
   *
   * This field is optional, in case there is no input for the command.
   */
  2: optional cas.Digest input_root_digest;

  /*
   * A timeout, in seconds, after which the execution should be killed. If the
   * timeout is absent, then the client is specifying that the execution should
   * continue as long as the server will let it. The server SHOULD impose a
   * timeout if the client does not specify one, however, if the client
   * specifies a timeout that is longer than the server's maximum timeout, the
   * server MUST reject the request.
   *
   * The timeout is a part of the Action message, and therefore two Actions with
   * different timeouts are different, even if they are otherwise identical.
   * This is because, if they were not, running an Action with a lower timeout
   * than is required might result in a cache hit from an execution run with a
   * longer timeout, hiding the fact that the timeout is too short. By encoding
   * it directly in the Action, a lower timeout will result in a cache miss and
   * the execution timeout will fail immediately, rather than whenever the cache
   * entry gets evicted.
   */
  3: i64 timeout_secs;

  /*
   * If true, then the Action's result cannot be cached.
   */
  4: bool do_not_cache;
}

/*
 * An OutputFile is similar to a FileNode, but it is tailored for output as
 * part of an ActionResult. It allows a full file path rather than only a
 * name.
 */
struct OutputFile {
  /*
   * The full path of the file relative to the working directory, including the
   * filename. The path separator is a forward slash '/'. Since this is a
   * relative path, it MUST NOT begin with a leading forward slash.
   */
  1: string path;

  /*
   * The digest of the file's content.
   */
  2: cas.Digest data_digest;

  /*
   * True if file is executable, false otherwise.
   */
  3: bool is_executable;
}

/*
 * An OutputDirectory is the output in an ActionResult corresponding to a
 * directory's full contents rather than a single file.
 */
struct OutputDirectory {
  /*
   * The full path of the directory relative to the working directory. The path
   * separator is a forward slash '/'. Since this is a relative path, it MUST
   * NOT begin with a leading forward slash. The empty string value is allowed,
   * and it denotes the entire working directory.
   */
  1: string path;

  /*
   * The digest of the encoded Tree struct containing the directory's contents.
   */
  2: cas.Digest tree_digest;
}

/*
 * Contains all the Directory structs in a single directory Merkle tree,
 * compressed into one message.
 */
struct Tree {
  /*
   * The root directory in the tree.
   */
  1: cas.Directory root;

  /*
   * All the child directories: the directories referred to by the root and,
   * recursively, all its children. In order to reconstruct the directory tree,
   * the client must take the digests of each of the child directories and then
   * build up a tree starting from the `root`.
   */
  2: list<cas.Directory> children;
}

/*
 * ExecutedActionMetadata contains details about a completed execution.
 */
struct ExecutedActionMetadata {
  /*
   * The name of the worker which ran the execution.
   */
  1: string worker;

  /*
   * When was the action added to the queue.
   */
  2: i64 queued_timestamp;

  /*
   * When the worker received the action.
   */
  3: i64 worker_start_timestamp;

  /*
   * When the worker completed the action, including all stages.
   */
  4: i64 worker_completed_timestamp;

  /*
   * When the worker started fetching action inputs.
   */
  5: i64 input_fetch_start_timestamp;

  /*
   * When the worker finished fetching action inputs.
   */
  6: i64 input_fetch_completed_timestamp;

  /*
   * When the worker started executing the action command.
   */
  7: i64 execution_start_timestamp;

  /*
   * When the worker completed executing the action command.
   */
  8: i64 execution_completed_timestamp;

  /*
   * When the worker started uploading action outputs.
   */
  9: i64 output_upload_start_timestamp;

  /*
   * When the worker finished uploading action outputs.
   */
  10: i64 output_upload_completed_timestamp;
}

/*
 * Represents the result on an Action that was run.
 */
struct ActionResult {
  /*
   * Output files of the Action. Will contain a single entry for every file
   * declared in the Action's output_files that existed after the action
   * completed.
   *
   * If the action does not produce the requested output, or produces a
   * directory where a regular file is expected or vice versa, then that output
   * will be omitted from the list.
   *
   * No order guarantees for the list.
   */
  1: list<OutputFile> output_files;

  /*
   * Output directories of the Action. Will contain a single entry for every
   * directory declared in the Action's output_directories that existed after
   * the action completed. No order guarantees for the list.
   */
  2: list<OutputDirectory> output_directories;

  /*
   * Exit code of the Command.
   */
  3: i32 exit_code;

  /*
   * The standard output buffer of the action. The server will determine, based
   * on the size of the buffer, whether to return in raw form or to return a
   * digest in the stdout_digest field, that points to the buffer. If neither is
   * set, then the buffer is empty.
   */
  4: optional binary stdout_raw;

  /*
   * The digest for a blob containing the standard output of the action. See
   * stdout_raw for when this will be set.
   */
  5: optional cas.Digest stdout_digest;

  /*
   * The standard error buffer of the action. The server will determine, based
   * on the size of the buffer, whether to return in raw form or to return a
   * digest in the stderr_digest field, that points to the buffer. If neither is
   * set, then the buffer is empty.
   */
  6: optional binary stderr_raw;

  /*
   * The digest for a blob containing the standard error of the action. See
   * stderr_raw for when this will be set.
   */
  7: optional cas.Digest stderr_digest;

  /*
   * The details of the execution that originally produced this result.
   */
  8: ExecutedActionMetadata execution_metadata;
}

/*
 * Specific error codes for ExecutionEngine failures.
 */
enum ExecutionEngineExceptionCode {
  /*
   * Non-specific exception code.
   */
  UNKNOWN = 0;

  /*
   * The execution was cancelled.
   */
  CANCELLED = 1;

  /*
   * The execution timed out.
   */
  TIMEOUT = 2;

  /*
   * The service is overloaded and can't take any more executions at the moment.
   */
  SERVICE_OVERLOADED = 3;

  /*
   * Action execution rejected. The action is either invalid, the service
   * lacks the required platrofm, or the timeout_secs in the action is higher
   * than the max allowed.
   */
  ACTION_REJECTED = 4;

  /*
   * Given execution id not known by the service.
   */
  UNKNOWN_EXECUTION_ID = 5;
}

/*
 * Represents an ExecutionEngine failure.
 */
exception ExecutionEngineException {
  /*
   * The exception status code.
   */
  1: ExecutionEngineExceptionCode code;

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
 * The possible stages of an execution.
 */
enum ExecuteOperationStage {
  /*
   * Default value; do not use.
   */
  UNKNOWN = 0;

  /*
   * Checking the result against the cache.
   */
  CACHE_CHECK = 1;

  /*
   * Currently idle, awaiting a free machine to execute.
   */
  QUEUED = 2;

  /*
   * Currently being executed by a worker.
   */
  EXECUTING = 3;

  /*
   * Finished execution.
   */
  COMPLETED = 4;
}

/*
 * Metadata about an ongoing ExecuteOperation.
 */
struct ExecuteOperationMetadata {
  /*
   * The current stage of execution.
   */
  1: ExecuteOperationStage stage;

  /*
   * The Digest of the Action being executed.
   */
  2: cas.Digest action_digest;
}

/*
 * A log stored in the CAS.
 */
struct LogFile {
  /*
   * The digest of the log contents.
   */
  1: cas.Digest digest;

  /*
   * Hint for the purpose of the log, and is set to true if can be usefully
   * displayed to a user. For example, can avoid displaying binary files to a
   * user.
   */
  2: bool human_readable;
}

/*
 * An ExecutionPolicy can be used to control the scheduling of an Action.
 */
struct ExecutionPolicy {
  /*
   * The priority (relative importance) of this action. Generally, a lower value
   * means that the action should be run sooner than actions having a greater
   * priority value, but the interpretation of a given value is server-
   * dependent. A priority of 0 means the *default* priority. Priorities may be
   * positive or negative, and such actions should run later or sooner than
   * actions having the default priority, respectively.
   *
   * The particular semantics
   * of this field is up to the server. In particular, every server will have
   * their own supported range of priorities, and will decide how these map into
   * scheduling policy.
   */
  1: i32 priority;
}

/*
 * A ResultsCachePolicy can be used for fine-grained control over how Action
 * outputs are stored in the ActionCache and CAS.
 */
struct ResultsCachePolicy {
  /*
   * The priority (relative importance) of this content in the overall cache.
   * Generally, a lower value means a longer retention time or other advantage,
   * but the interpretation of a given value is server-dependent. A priority of
   * 0 means a *default* value, decided by the server.
   *
   * The particular semantics of this field is up to the server. In particular,
   * every server will have their own supported range of priorities, and will
   * decide how these map into retention/eviction policy.
   */
  1: i32 priority;
}

struct ExecuteRequestMetadata {
  1: optional string artillery_trace_id;
}

/*
 * A request message for Execution.
 */
struct ExecuteRequest {
  /*
   * If true, the action will be executed anew even if its result was already
   * present in the cache. If false, the result may be served from the
   * ActionCache.
   */
  1: bool skip_cache_lookup;

  /*
   * The digest of the Action to execute.
   */
  2: cas.Digest action_digest;

  /*
   * An optional policy for execution of the action. The server will have a
   * default policy if this is not provided.
   */
  3: optional ExecutionPolicy execution_policy;

 /*
  * An optional policy for the results of this execution in the remote cache.
  * The server will have a default policy if this is not provided. This may be
  * applied to both the ActionResult and the associated blobs.
  */
 4: optional ResultsCachePolicy results_cache_policy;

 5: optional ExecuteRequestMetadata metadata;
}

/*
 * The eventual response for ExecutionEngine.execute, which will be contained
 * in the response field of the Execution.
 */
struct ExecuteResponse {
  /*
   * The result of the Action. Can be missing in case of an error status.
   */
  1: optional ActionResult result;

  /*
   * True if the result was served from cache, false if it was executed.
   */
  2: bool cached_result;

  /*
   * If an exception is present, it indicates that the Action did not
   * finish execution, like timing out, or failed with an error.
   *
   * Servers MUST use this field for errors in execution, rather than the
   * exception field on the ExecuteOperation object.
   *
   * If present the result MUST NOT be cached.
   */
  3: optional ExecutionEngineException ex;

  /*
   * An optional list of log outputs the server provides. These can include
   * execution-specific logs, primarily intended for debugging issues that
   * may be outside of the actual job execution, like worker setup. Keys should
   * be human readable so they can be displayed to a user.
   */
  4: map<string, LogFile> server_logs;
}

/*
 * A running execution that is the result of a call to ExecutionEngine.execute.
 * Fulfills a similar role to google.longrunning.Operation.
 */
struct ExecuteOperation {
  /*
   * A server-assigned id which is unique within the system. To be used for
   * querying the Execution's state.
   */
  1: string execution_id;

  /*
   * Contains progress information as well as common metadata such as
   * execution stage.
   */
  2: ExecuteOperationMetadata metadata;

  /*
   * If the value is false, it means the execution is still in progress. If
   * true, the execution has completed, and error or response will be available.
   */
  3: bool done;

  /*
   * Report potential errors while creating/running the Execution itself,
   * like failure to start the Execution or cancellation. This field DOES NOT
   * indicate anything about the result of the Execution itself ("user errors"),
   * which will be reported in ExecuteResponse.
   *
   * Executions that completed but have logically failed executing their Action
   * will won't be reported in this field.
   */
  4: optional ExecutionEngineException ex;

  /*
   * The normal response of the execution in case of success. This does not mean
   * that there aren't any logical errors.
   */
  5: optional ExecuteResponse response;

  /*
   * FB-specific extension for reporting status via HTTP long-polling. Contains
   * the HTTP endpoint that the caller can use to query status.
   *
   * Ex. "http://devbig466.frc2.facebook.com:1234/status/long_poll"
   */
  6: optional string status_http_endpoint;
}

/*
 * Reponse struct used to stream long-polling updates.
 */
struct GetExecuteOperationMultiResponse {
  1: map<string, ExecuteOperation> operations,
}

/*
 * Request message for ExecutionEngine.getExecuteOperation.
 */
struct GetExecuteOperationRequest {
  /*
   * The server-assigned id.
   */
  1: string execution_id;
}

/*
 * The ExecutionEngine is used to execute Actions on remote workers.
 */
service ExecutionEngine {
  /*
   * Schedule an execution to be executed remotely.
   *
   * In order to execute an Action, the client must first upload all of the
   * inputs, as well as the Command to run, into the CAS.
   *
   * The input Action MUST meet the specific canonicalization requirements by
   * the system.
   *
   * Returns an ExecuteOperation describing the ongoing execution operation,
   * with the eventual response field ExecuteResponse:
   *
   *   ExecuteRequest => ExecuteOperation ... => ExecuteResponse
   *      (Action)                                (ActionResult)
   */
  ExecuteOperation execute(
    1: ExecuteRequest request,
  ) throws (
    1: ExecutionEngineException ex,
  );

  /*
   * Gets an ongoing execution operation. Clients can use this method to poll
   * the operation and wait for execution response.
   */
  ExecuteOperation getExecuteOperation(
    1: GetExecuteOperationRequest request,
  ) throws (
    1: ExecutionEngineException ex,
  );
}
