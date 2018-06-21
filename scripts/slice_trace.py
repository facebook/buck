from __future__ import absolute_import, division, print_function, unicode_literals

import argparse
import codecs
import collections
import json
import math
import os


def parse_args():
    description = """Slice JSON .trace file into smaller pieces."""

    parser = argparse.ArgumentParser(description=description)
    parser.add_argument("trace_file", help="Path to trace file.")
    parser.add_argument("--slice_size", help="Size of slice in Mb.", default=230)
    parser.add_argument(
        "--output_name",
        help="Format of slices, {slice} is the slice index.",
        default="{orig}_{slice}.trace",
    )
    parser.add_argument(
        "--context_events_count",
        help="Number of events to carry over between slices for context.",
        default=50,
    )
    return parser.parse_args()


def dump_slice(args, slice_index, event_list):
    orig_file, _ = os.path.splitext(args.trace_file)
    slice_file_name = args.output_name.format(slice=slice_index, orig=orig_file)
    print("Writing slice to", slice_file_name)
    with codecs.open(slice_file_name, "w", "utf-8") as slice_file:
        json.dump(event_list, slice_file)


def main():
    args = parse_args()

    slice_size_mb = int(args.slice_size)
    context_events_count = int(args.context_events_count)
    trace_size_mb = os.path.getsize(args.trace_file) / (1024 * 1024)
    slice_ratio = min(1, slice_size_mb / float(trace_size_mb))
    assert slice_ratio > 0

    with codecs.open(args.trace_file, "r", "utf-8") as trace:
        data = json.load(trace)

    # Accurate "enough". The default slice size is less than 256MB.
    events_per_trace = int(len(data) * slice_ratio)
    print(
        "Total events in trace file:", len(data), "Events per trace", events_per_trace
    )

    slice_index = 0
    start_index = 0
    while start_index < len(data):
        i = max(0, start_index - context_events_count)
        j = start_index + events_per_trace + context_events_count
        dump_slice(args, slice_index, data[i:j])
        slice_index += 1
        start_index += events_per_trace


if __name__ == "__main__":
    main()
