# Copyright 2018-present Facebook, Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may
# not use this file except in compliance with the License. You may obtain
# a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations
# under the License.

"""This is a self-profiling tool."""
import contextlib
import functools
import inspect
import itertools
import os
import sys
import threading
import time
from collections import namedtuple

PROFILING_TIMER_DELAY = 0.01
BLACKLIST = [
    "copy.py:_deepcopy_dict",
    "copy.py:_deepcopy_list",
    "__init__.py:query",
    "encoder.py:_iterencode",
    "encoder.py:_iterencode_list",
    "encoder.py:_iterencode_dict",
]
PROFILING_USEFULNESS_THRESHOLD_DELAY = 0.02
HIGHLIGHTS_THRESHOLD_DELAY = 0.5


StackFrame = namedtuple("StackFrame", "filename line function")


class AggregatedStackNode(object):
    def __init__(self, frame, callstack):
        self.frame = frame
        self.callstack = callstack
        self.duration = 0
        self.children = {}
        self.useful = True

    def add_children(self, frame, callstack, duration):
        key = frame.filename + ":" + str(frame.line)
        if key in self.children:
            subnode = self.children[key]
            subnode.duration += duration
        else:
            subnode = AggregatedStackNode(frame, callstack)
            self.children[key] = subnode
            subnode.duration += duration
        return subnode

    def add_callstack(self, callstack, duration, reverse_callstack):
        current_node = self
        if reverse_callstack:
            callstack = reversed(callstack)
        for frame in callstack:
            next_node = current_node.add_children(frame, callstack, duration)
            current_node = next_node


class Profiler(object):
    def __init__(self, reverse_callstack):
        # The callstack storage
        self._aggregated_callstack = AggregatedStackNode(None, None)
        self._stopped = False
        self.total_time = 0
        self.start_time = 0
        self._thread = None
        self._last_start_time = 0
        self._stopthread = None
        self._reverse_callstack = reverse_callstack

    def start(self):
        """Start the profiler."""
        self.start_time = time.time()
        self._stopthread = threading.Event()
        self._last_start_time = time.time()
        frame = inspect.currentframe()
        frames_items = sys._current_frames().items()  # pylint: disable=protected-access
        tid = [k for k, f in frames_items if f == frame][0]
        self._thread = threading.Thread(
            target=self._stack_trace_collection_thread,
            args=(tid,),
            name="sampler thread",
        )
        self._thread.start()

    def stop(self):
        """Stop the profiler."""
        self.total_time = time.time() - self.start_time
        self._stopped = True
        self._stopthread.set()
        self._thread.join()
        self._stopthread = None

    def _stack_trace_collection_thread(self, tid):
        while not self._stopthread.is_set():
            now = time.time()
            frame = sys._current_frames()[tid]  # pylint: disable=protected-access
            callstack = Profiler._frame_stack_to_call_stack_frame(frame)
            self._aggregated_callstack.add_callstack(
                callstack, now - self._last_start_time, self._reverse_callstack
            )
            self._last_start_time = now
            time.sleep(PROFILING_TIMER_DELAY)

        self._stopthread.clear()

    @staticmethod
    def _frame_stack_to_call_stack_frame(frame):
        result = []
        while frame:
            stack_frame = StackFrame(
                frame.f_code.co_filename, frame.f_lineno, frame.f_code.co_name
            )
            frame = frame.f_back
            result.append(stack_frame)
        result.reverse()
        return list(
            itertools.takewhile(
                lambda x: not Profiler._is_stack_frame_in_blacklist(x), result
            )
        )

    @staticmethod
    def _is_stack_frame_in_blacklist(frame):
        """Returns wether the stack frame should be ignored."""
        filename = os.path.basename(frame.filename)
        call_identifier = "{}:{}".format(filename, frame.function)
        return call_identifier in BLACKLIST

    @staticmethod
    def _items_sorted_by_duration(items):
        return sorted(
            items, key=lambda current_child: current_child.duration, reverse=True
        )

    def generate_report(self):
        # type: () -> str
        """Generate string with a nice visualization of the result of the profiling."""
        Profiler._recursive_mark_useful_leaf(self._aggregated_callstack)

        content = ["# Highlights\n"]
        highlights = []
        if self._reverse_callstack:
            for node in self._aggregated_callstack.children.values():
                if node.duration > HIGHLIGHTS_THRESHOLD_DELAY:
                    highlights.append(node)
        else:
            Profiler._recursive_collect_highlights_report(
                self._aggregated_callstack, highlights
            )
        highlights = Profiler._items_sorted_by_duration(highlights)

        content += Profiler._generate_highlights_report(highlights)

        content += ["\n", "# More details\n"]
        content += self._generate_callstack_report(highlights)
        return "".join(content)

    @staticmethod
    def _is_useful_leaf(node):
        """Returns whether a leaf needs to be highlighted."""
        if node.frame is None:
            return True

        filename = os.path.basename(node.frame.filename)
        dirname = os.path.dirname(node.frame.filename)
        if node.duration < PROFILING_USEFULNESS_THRESHOLD_DELAY:
            return False

        return True

    @staticmethod
    def _recursive_mark_useful_leaf(node):
        """Mark the node as needing to be shown."""
        useful = Profiler._is_useful_leaf(node)

        children = node.children.values()
        for child in Profiler._items_sorted_by_duration(children):
            Profiler._recursive_mark_useful_leaf(child)
            if child.useful:
                useful = True
            else:
                frame = child.frame
                key = frame.filename + ":" + str(frame.line)
                node.children.pop(key, None)

        node.useful = useful

    def _generate_callstack_report(self, highlights):
        """Generate a string with a vizualisation of the aggregated call stack tree."""
        highlights_set = set()
        if not self._reverse_callstack:
            for item in highlights:
                frame = item.frame
                highlights_set.add(frame.filename + ":" + str(frame.line))
        return Profiler._recursive_write_callstack_report(
            self._aggregated_callstack, "", highlights_set
        )

    @staticmethod
    def _recursive_write_callstack_report(node, prefix_str, highlights_set):
        """Generate an aggregated call stack tree that looks like this one:

        |-0.04 glob_watchman (glob_watchman.py:103)
        | \-0.04 wrapped (util.py:76)
        |   \-0.04 glob (buck.py:328)
        |     \-0.04 _glob (buck.py:689)
        |       \-0.04 <module> (BUCK:58)
        |         \-0.04 _process (buck.py:930)
        |           \-0.04 _process_build_file (buck.py:970)
        |             \-0.04 process (buck.py:976)
        |               \-0.04 process_with_diagnostics (buck.py:1085)
        |                 \-0.04 main (buck.py:1379)
        |                   \-0.04 <module> (__main__.py:11)
        |-0.03 _update_functions (buck.py:620)
        | \-0.02 _set_build_env (buck.py:772)
        |   \-0.02 __enter__ (contextlib.py:17)
        |     \-0.02 _process (buck.py:889)
        |       \-0.02 _process_build_file (buck.py:970)
        |         \-0.02 process (buck.py:976)
        |           \-0.02 process_with_diagnostics (buck.py:1085)
        |             \-0.02 main (buck.py:1379)
        |               \-0.02 <module> (__main__.py:11)
        \-0.02 encode (encoder.py:209)
          \-0.02 encode_result (buck.py:1051)
            \-0.02 java_process_send_result (buck.py:1112)
              \-0.02 process_with_diagnostics (buck.py:1104)
                \-0.02 main (buck.py:1379)
                  \-0.02 <module> (__main__.py:11)
        """

        children = node.children.values()
        nodes_count = len(children)

        result = []
        for i, child in enumerate(Profiler._items_sorted_by_duration(children)):
            frame = child.frame
            highlight_key = frame.filename + ":" + str(frame.line)
            highlighted = highlight_key in highlights_set

            if i == nodes_count - 1:
                node_prefix_str = prefix_str + "\\-"
                next_prefix_str = prefix_str + "  "
            else:
                node_prefix_str = prefix_str + "|-"
                next_prefix_str = prefix_str + "| "
            str_to_write = node_prefix_str + "{:.2f} {} ({}:{})".format(
                child.duration,
                frame.function,
                os.path.basename(frame.filename),
                frame.line,
            )
            if highlighted:
                if len(str_to_write) < 120:
                    highlighted_str = "-" * (120 - len(str_to_write))
                else:
                    highlighted_str = "-" * 10
            else:
                highlighted_str = ""

            result += [str_to_write, highlighted_str, "\n"]
            result += Profiler._recursive_write_callstack_report(
                child, next_prefix_str, highlights_set
            )

        return result

    @staticmethod
    def _recursive_collect_highlights_report(node, highlights):
        """Store into `highlights` the frames that needs to be highlighted."""
        children = node.children.values()
        nodes_count = len(children)

        if nodes_count == 0:
            if node.duration > HIGHLIGHTS_THRESHOLD_DELAY:
                highlights.append(node)
        else:
            children = Profiler._items_sorted_by_duration(children)
            for child in children:
                Profiler._recursive_collect_highlights_report(child, highlights)

    @staticmethod
    def _generate_highlights_report(highlights):
        """Write the list of highlights to the given file."""
        result = []
        for node in highlights:
            frame = node.frame
            filename = os.path.basename(frame.filename)
            result.append(
                "{:.2f} {} ({}:{})\n".format(
                    node.duration, frame.function, filename, frame.line
                )
            )
            # Output the stack frame of that highlight.
            for current_frame in node.callstack:
                if current_frame.function == "<module>":
                    continue
                item_filename = os.path.basename(current_frame.filename)
                result.append(
                    "        {} ({}:{})\n".format(
                        current_frame.function, item_filename, current_frame.line
                    )
                )
                if (
                    frame.filename == current_frame.filename
                    and frame.line == current_frame.line
                    and frame.function == current_frame.function
                ):
                    break
            result.append("\n")
        return result


class Trace:
    """ Trace object represents a single trace along with any children.
        The object keeps track of description, stats key (used for aggregation)
        along with relevant timing information.
    """

    def __init__(self, description, key=None):
        # Trace description
        self._description = description
        # Tracer "type/key"
        self._key = key
        # This trace start time
        self._start_time = time.time()
        # This trace end time.  None implies the trace is still active.
        self._end_time = None
        # Child traces.
        self._children = []
        # This trace start time relative to the parent trace start.
        self._relative_start_time = None

    def mark_finished(self):
        self._end_time = time.time()

    def is_active(self):
        return self._end_time is None

    def get_key(self):
        return self._key

    def duration(self):
        if self.is_active():
            return time.time() - self._start_time
        else:
            return self._end_time - self._start_time

    def add_child_trace(self, trace):
        self._children.append(trace)
        trace._relative_start_time = trace._start_time - self._start_time

    def get_children(self):
        return self._children

    def __repr__(self):
        return (
            "{relative_start}{description} "
            "{start_time:.6f} - {end_time:.6f} ({duration}{active})\n".format(
                relative_start=" (+{:.6f}s) ".format(self._relative_start_time)
                if self._relative_start_time
                else "",
                description=self._description,
                start_time=self._start_time,
                end_time=self._end_time if self._end_time else 0.0,
                duration="{:.6f}".format(self.duration()),
                active=" active" if self.is_active() else "",
            )
        )


class Tracer:
    """ Tracer object is a static object used to collect and emit traces.
        Before start/stop tracer methods can be used, Tracer must be enable()d.
    """

    class _State:
        traces = []
        active = []

    _state = None

    @staticmethod
    def enable():
        if Tracer._state is None:
            Tracer._state = Tracer._State()

    @staticmethod
    def is_enabled():
        return Tracer._state is not None

    @staticmethod
    def start_trace(descr, key=None):
        # type str -> Trace
        assert Tracer.is_enabled()
        state = Tracer._state
        trace = Trace(descr, key)
        if state.active:
            state.active[-1].add_child_trace(trace)
        else:
            state.traces.append(trace)

        state.active.append(trace)
        return trace

    @staticmethod
    def end_trace(trace):
        # type Trace -> ()
        assert Tracer.is_enabled()
        trace.mark_finished()
        active = Tracer._state.active.pop()
        assert active == trace

    @staticmethod
    def get_all_traces_and_reset():
        # type () -> str
        traces = Tracer._state.traces
        stats = {}
        res = "\n\n### TRACES\n\n"
        Tracer._state.traces = []

        while traces:
            # Queue of traces to print: trace, indent prefix, parent trace
            queue = [(traces.pop(0), [], None)]
            while queue:
                (trace, indent, parent) = queue.pop()
                res += "{}{}".format("".join(indent), trace)
                if trace.get_key():
                    stats.setdefault(trace.get_key(), [0, 0.0])
                    stats[trace.get_key()][0] += 1
                    stats[trace.get_key()][1] += trace.duration()

                if trace.get_children():
                    if indent:
                        if indent[-1] == "-":
                            indent[-2:] = list(" | \\-")
                        if len(indent) > 80:
                            # Arbitrary max depth.  Indicate by "*" to stop nesting.
                            indent[-1] = "*"
                    else:
                        indent = list("| \\-")
                    queue.extend(
                        [(t, indent, trace) for t in reversed(trace.get_children())]
                    )

        if stats:
            res = (
                "\n\n### TRACE SUMMARY\n"
                + "\n".join(
                    [
                        "{:10s}: n={} total_duration={:.6f}s".format(
                            key, stat[0], stat[1]
                        )
                        for key, stat in stats.items()
                    ]
                )
                + "\n"
                + res
            )

        return res


@contextlib.contextmanager
def scoped_trace(trace_description, stats_key=None):
    """ Creates scoped trace object with the given description.
        If stats_key is specified, this key is used to produced aggregate
        statistics for all traces with the same stats_key (e.g IO, CPU, etc)
        This decorator is a no-op if the tracer is not enabled.
    """

    trace = (
        Tracer.start_trace(trace_description, stats_key)
        if Tracer.is_enabled()
        else None
    )
    try:
        yield
    finally:
        if trace:
            Tracer.end_trace(trace)


def traced(description=None, stats_key=None):
    """ Decorator for tracing entire function
        This decorator is a no-op if the tracer is not enabled.
    """

    if description is None:
        description = stats_key or "Traced Function"

    def decorator(func):
        @functools.wraps(func)
        def wrapped(*args, **kwargs):
            with scoped_trace(description, stats_key):
                return func(*args, **kwargs)

        return wrapped

    return decorator


def emit_trace(message, *args, **kwargs):
    """ Emits a single trace message
        args/kwargs are passed to format() call
        This decorator is a no-op if the tracer is not enabled.
    """
    if Tracer.is_enabled():
        Tracer.end_trace(Tracer.start_trace(message.format(*args, **kwargs)))
