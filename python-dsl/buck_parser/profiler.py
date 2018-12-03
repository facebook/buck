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
