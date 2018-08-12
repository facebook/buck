include "cas.thrift"

namespace java com.facebook.remoteexecution.executionengine

/*
 * A tuple of name and value. Names are always strings. Values may be numeric,
 * but for maximum flexibility are represented as strings as well.
 */
struct Property {
  1: string name,
  2: string value,
}

/*
 * A tuple of name and value representing a *dynamic* resource, such as: CPU,
 * RAM, disk, etc. Names are always strings and values numeric.
 */
struct Resource {
  1: string name,
  2: double value,
}

/*
 * Describe a required device to connected to a worker host.
 */
struct RequiredDevice {
  /*
   * Properties characterizing the device. E.g., OS, CPU architecture, etc.
   */
  1: list<Property> properties,
}

/*
 * Describe the host on which a worker runs and the connected devices.
 */
struct WorkerHost {
  /*
   * Fundamental features that apply to the host, and not to individual devices.
   * E.g., OS, CPU architecture, total available memory, etc.
   */
  1: list<Property> properties,

  /*
   * Dynamic resources *available* for leasing (i.e. not guaranteed for other
   * leases), such as: CPU, RAM, disk, etc. Should not include total values, but
   * only resources free for leasing.
   *
   * TODO: Do we want to make the distinction between "un-leased" resources, and
   *       system-available resourced? Is that interesting? (but can also be
   *       very time-sensitive to when we measure/report)
   */
  2: list<Resource> resources,

  /*
   * A list of any attached devices *available* for leasing.
   */
  3: list<Device> devices,
}

/*
 * Describe the required host on which to run a worker and the connected devices
 */
struct RequiredWorkerHost {
  /*
   * Fundamental features that apply to the host, and not to individual devices.
   * E.g., OS, CPU architecture, total available memory, etc.
   */
  1: list<Property> properties,

  /*
   * Dynamic resources required for execution, such as: CPU, RAM, disk, etc.
   */
  2: list<Resource> resources,

  /*
   * A list of required attached devices.
   */
  3: list<RequiredDevice> devices,
}

/*
 * Describe a device connected to a worker host.
 */
struct Device {
  /*
   * A logical "handle" for a device. Unique within a worker host.
   */
  1: string name,

  /*
   * Properties characterizing the device. E.g., OS, CPU architecture, etc.
   */
  2: list<Property> properties,
}

/*
 * Describe the *required* execution environment. This is the top-level object
 * that captures the entire environment. Can include external resources,
 * custom networking rewuirements, and anything external to a worker host.
 */
struct Requirements {
  /*
   * Required worker host.
   */
  1: RequiredWorkerHost worker_host,
}

/*
 * Describe the *available* execution environment. This is the top-level object
 * that captures the entire environment of a worker. Can include external
 * resources, custom networking rewuirements, and anything external to a worker
 * host.
 */
struct Capabilities {
  /*
   * Available worker host.
   */
  1: WorkerHost worker_host,
}

/*
 * A variable to be set in the running program's environment.
 */
struct EnvironmentVariable {
  /*
   * The variable name.
   */
  1: string name,

  /*
   * The variable value.
   */
  2: string value,
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
   * is evaluated with respect to the input root, or an absolute path. The
   * working directory will always be the input root.
   */
  1: list<string> arguments,

  /*
   * The environment variables to set when running the program. The worker may
   * provide its own default environment variables; these defaults can be
   * overridden using this field. Additional variables can also be specified.
   *
   * In order to ensure that equivalent Commands always hash to the same value,
   * the environment variables MUST be lexicographically sorted by name. Sorting
   * of strings is done by code point or, equivalently, by the UTF-8 bytes.
   */
  2: list<EnvironmentVariable> environment_variables,
}

/*
 * Captures all the information about an execution that's required to
 * reproduce it. A single Action represents a repeatable action that can be
 * performed by the Execution Engine.
 */
struct Action {
  /*
   * The digest of the Command to run. MUST be present in the CAS.
   */
  1: cas.Digest command_digest,

  /*
   * The digest of the root Directory for the input files. Files in the
   * directory tree will be available in the correct location on the remote
   * worker before the command is executed. The root directory, as well as
   * the blobs referred, MUST be in the CAS.
   *
   * This field is optional, in case there is no input for the command.
   */
  2: optional cas.Digest input_root_digest,

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
   */
  3: list<string> output_files,

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
   */
  4: list<string> output_directories,

  /*
   * The requirements demanded by this lease.
   */
  5: Requirements requirements,

  /*
   * A timeout, in seconds, after which the execution should be killed. If the
   * client specifies a timeout that is longer than the server's maximum
   * timeout, the server MUST reject the request.
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
  6: i64 timeout_secs,

  /*
   * If true, then the Action's result cannot be cached.
   */
  7: bool do_not_cache,
}

/*
 * An OutputFile is similar to a FileNode, but it is tailored for output as
 * part of an ActionResult. It allows a full file path rather than only a
 * name, and allows the server to include content inline.
 */
struct OutputFile {
  /*
   * The full path of the file relative to the input root, including the
   * filename. The path separator is a forward slash '/'. Since this is a
   * relative path, it MUST NOT begin with a leading forward slash.
   */
  1: string path,

  /*
   * The digest of the file's content.
   */
  2: cas.Digest data_digest,

  /*
   * True if file is executable, false otherwise.
   */
  3: bool is_executable,

  /*
   * The raw content of the file.
   *
   * This field may be used by the server to provide the content of a file
   * inline in an ActionResult and avoid requiring that the client make a
   * separate call to ContentAddressableStorage.GetBlob to retrieve it.
   *
   * The client SHOULD NOT assume that it will get raw content with any request,
   * and always be prepared to retrieve it via the digest field.
   */
  4: optional binary content,
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
  1: string path,

  /*
   * The digest of the encoded Tree struct containing the directory's contents.
   */
  2: cas.Digest tree_digest,
}

/*
 * Represents the result on an Action that was run.
 */
struct ActionResult {
  /*
   * Output files of the Action. Will contain a single entry for every file
   * declared in the Action's output_files that existed after the action
   * completed. No order guarantees for the list.
   */
  1: list<OutputFile> output_files,

  /*
   * Output directories of the Action. Will contain a single entry for every
   * directory declared in the Action's output_directories that existed after
   * the action completed. No order guarantees for the list.
   */
  2: list<OutputDirectory> output_directories,

  /*
   * Exit code of the Command.
   */
  3: i32 exit_code,

  /*
   * The standard output buffer of the action. The server will determine
   * whether to return in raw form or to return a digest in the stdout_digest
   * field, that points to the buffer. If neither is set, then the buffer is
   * empty.
   */
  4: optional binary stdout_raw,

  /*
   * The digest for a blob containing the standard output of the action. See
   * stdout_raw for when this will be set.
   */
  5: optional cas.Digest stdout_digest,

  /*
   * The standard error buffer of the action. The server will determine
   * whether to return in raw form or to return a digest in the stderr_digest
   * field, that points to the buffer. If neither is set, then the buffer is
   * empty.
   */
  6: optional binary stderr_raw,

  /*
   * The digest for a blob containing the standard error of the action. See
   * stderr_raw for when this will be set.
   */
  7: optional cas.Digest stderr_digest,

  /*
   * The duration of the command execution, rounded to the closest second. This
   * refers only to the amount of time the command took to execute from the
   * moment its process was spawned to the moment it ended, without including
   * the time to prepare the environment or upload the result.
   */
  8: i64 duration_secs,
}

/*
 * Exception thrown when the service is overloaded and can't take any more
 * executions at the moment.
 */
exception ServiceOverloadedException {
  1: string message,
}

/*
 * Thrown by the service if the execution of an action is rejected. It means
 * that the action is either invalid or the service lacks the capabilities or
 * the timeout_secs in the action is higher than the max allowed.
 */
exception RejectedActionException {
  1: string message,
}

/*
 * Thrown if the execution id given by the client is not known by the service.
 */
exception UnknownExecutionIdException {
  1: string message,
}

/*
 * Thrown if the client requests execution with a previously generated id.
 */
exception NonUniqueExecutionIdException {
  1: string message,
}

/*
 * The possible stages of an execution.
 */
enum ExecutionStage {
  /*
   * Default value; do not use.
   */
  UNKNOWN = 0,

  /*
   * Checking the result against the cache.
   */
  CACHE_CHECK = 1,

  /*
   * Currently idle, awaiting a free machine to execute.
   */
  QUEUED = 2,

  /*
   * Currently being executed by a worker.
   */
  EXECUTING = 3,

  /*
   * Finished execution.
   */
  COMPLETED = 4,
}

enum ExecutionStatus {
  /*
   * Default value; do not use.
   */
  UNKNOWN = 0,

  /*
   * Completed with no errors.
   */
  OK = 1,

  /*
   * The execution was canceled.
   */
  CANCELED = 2,

  /*
   * The execution timed out.
   */
  TIMEOUT = 3,
}

/*
 * The eventual response for ExecutionEngine.execute, which will be contained
 * in the response field of the Execution.
 */
struct ExecutionResult {
  /*
   * The result of the Action. Can be missing in case of an error status.
   */
  1: optional ActionResult action_result,

  /*
   * True if the result was served from cache, false if it was executed.
   */
  2: bool cached_result,

  /*
   * If the status is other than OK, it indicates that the Action did not
   * finish execution, like timing out or being canceled.
   *
   * If the status code is other than OK, then the result MUST NOT be cached.
   */
  3: ExecutionStatus status,
}

/*
 * Metadata about an ongoing Execution.
 */
struct ExecutionMetadata {
  /*
   * The current stage of execution.
   */
  1: ExecutionStage stage,

  /*
   * The Digest of the Action being executed.
   */
  2: cas.Digest action_digest,
}

/*
 * A running execution that is the result of a call to ExecutionEngine.execute.
 */
struct ExecutionState {
  /*
   * An id which is unique within the system. To be used for querying the
   * Execution's state.
   */
  1: string execution_id,

  /*
   * Contains progress information as well as common metadata such as
   * execution stage.
   */
  2: ExecutionMetadata metadata,

  /*
   * If the value is false, it means the execution is still in progress. If
   * true, the execution has completed, and result will be available.
   */
  3: bool done,

  /*
   * The result of the execution. Only available if execution is done.
   */
  4: optional ExecutionResult result,
}

struct GetExecutionStateRequest {
  /*
   * The id of the execution.
   */
  1: string execution_id,
}

struct GetExecutionStateResponse {
  /*
   * The current state of the execution. If the id is invalid/unknown to this
   * execution engine, this won't be set (i.e. it'll be null).
   */
  1: optional ExecutionState state,
}

struct ExecuteRequest {
  /*
   * The instance of the execution system to operate against. Instances enable
   * support for multi-tenancy. The system may support multiple instances, each
   * with their own worker pool, cache, etc.
   */
  1: string instance_name,

  /*
   * The action to be performed.
   */
  2: Action action,

  /*
   * If true, the action will be executed anew even if its result was already
   * present in the cache. If false, the result may be served from the
   * ActionCache.
   */
  3: bool skip_cache_lookup;

  /*
   * A client-assigned execution id used to refer to this execution. This id
   * must be unique. If one isn't given, it will be assigned by the server.
   */
  4: optional string execution_id,
}

struct ExecuteResponse {
  /*
   * The initial state of the execution.
   */
  1: ExecutionState state,
}

struct CancelExecutionRequest {
  /*
   * The id of the execution to cancel.
   */
  1: string execution_id,
}

struct CancelExecutionResponse {
  /*
   * The id of the execution requested to be canceled.
   */
  1: string execution_id,
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
   * Returns an Execution describing the resulting execution, with the eventual
   * response field ExecuteResponse.
   */
  ExecuteResponse execute(
    1: ExecuteRequest request,
  ) throws (
    1: ServiceOverloadedException service_overloaded,
    2: RejectedActionException rejected_action,
    3: NonUniqueExecutionIdException non_unique_execution_id,
  );

  /*
   * Gets the latest state of an existing execution. Clients can use this method
   * to poll the execution result.
   */
  GetExecutionStateResponse getExecutionState(
    1: GetExecutionStateRequest request,
  ) throws (
    1: UnknownExecutionIdException unknown_execution_id,
  );

  /*
   * Requests the cancellation of an execution. The cancellation might not
   * happen immediately.
   */
  CancelExecutionResponse cancelExecution(
    1: CancelExecutionRequest request,
  ) throws (
    1: UnknownExecutionIdException unknown_execution_id,
  );
}
