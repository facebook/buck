import argparse
import itertools
import json
import logging
import os
import subprocess
import sys
import tempfile
import zipfile


class CacheEntry(object):
    pass


def get_cache_entry(path):
    with zipfile.ZipFile(path) as f:
        entry_map = {os.path.basename(n): n for n in f.namelist()}
        entry = CacheEntry()
        entry.target = f.read(entry_map['TARGET']).strip()
        entry.rule_key = f.read(entry_map['RULE_KEY']).strip()
        entry.deps = json.loads(f.read(entry_map['DEPS']))
        entry.path = path
        return entry


def get_cache_inventory():
    inventory = {}
    for item in os.listdir('buck-cache'):
        entry = get_cache_entry(os.path.join('buck-cache', item))
        inventory[entry.target] = entry
    return inventory


def get_missing_cache_entries(inventory):
    """
    Find and return all entries missing in the cache.
    """

    missing_entries = {}

    for entry in inventory.itervalues():
        if not os.path.exists(entry.path):
            missing_entries[entry.target] = entry

    return missing_entries


def clear_cache():
    subprocess.check_call(['rm', '-rf', 'buck-cache'])


def clear_output():
    subprocess.check_call(['rm', '-rf', 'buck-out'])


def run_buck(buck, *args):
    logging.info('Running {} {}'.format(buck, ' '.join(args)))

    # Always create a temp file, in case we need to serialize the
    # arguments to it.
    with tempfile.NamedTemporaryFile() as f:

        # If the command would be too long, put the args into a file and
        # execute that.
        if len(args) > 30:
            for arg in args:
                f.write(arg)
                f.write(os.linesep)
            f.flush()
            args = ['@' + f.name]

        return subprocess.check_output([buck] + list(args))


def preorder_traversal(roots, deps, callback):
    """
    Execute the given callback during a preorder traversal of the graph.
    """

    # Keep track of all the nodes processed.
    seen = set()

    def traverse(node, callback, chain):

        # Make sure we only visit nodes once.
        if node in seen:
            return
        seen.add(node)

        # Run the callback with the current node and the chain of parent nodes we
        # traversed to find it.
        callback(node, chain)

        # Recurse on depednencies, making sure to update the visiter chain.
        for dep in deps[node]:
            traverse(dep, callback, chain=chain + [node])

    # Traverse starting from all the roots.
    for root in roots:
        traverse(root, callback, [])


def build(buck, targets):
    """
    Verify that each of the actions the run when building the given targets
    run correctly using a top-down build.
    """

    # Now run a build to populate the cache.
    logging.info('Running a build to populate the cache')
    run_buck(buck, 'build', *targets)

    # Find all targets reachable via the UI.
    out = run_buck(buck, 'audit', 'dependencies', '--transitive', *targets)
    ui_targets = set(out.splitlines())
    ui_targets.update(targets)

    # Grab an inventory of the cache and use it to form a dependency map.
    cache_inventory = get_cache_inventory()
    dependencies = {n.target: n.deps for n in cache_inventory.itervalues()}

    # Keep track of all the processed nodes so we can print progress info.
    processed = set()

    # The callback to run for each build rule.
    def handle(current, chain):
        logging.info(
            'Processing {} ({}/{})'
            .format(current, len(processed), len(dependencies.keys())))
        processed.add(current)

        # Empty the previous builds output.
        logging.info('Removing output from previous build')
        clear_output()

        # Remove the cache entry for this target.
        entry = cache_inventory[current]
        os.remove(entry.path)
        logging.info('  removed {} => {}'.format(current, entry.path))

        # Now run the build using the closest UI visible ancestor target.
        logging.info('Running the build to check ' + current)
        for node in itertools.chain([current], reversed(chain)):
            if node in ui_targets:
                run_buck(buck, 'build', '--just-build', current, node)
                break
        else:
            assert False, 'couldn\'t find target in UI: ' + node

        # We should *always* end with a full cache.
        logging.info('Verifying cache...')
        missing = get_missing_cache_entries(cache_inventory)
        assert len(missing) == 0, '\n'.join(sorted(missing.keys()))

    preorder_traversal(targets, dependencies, handle)


def test(buck, targets):
    """
    Test that we can run tests when pulling from the cache.
    """

    # Find all test targets.
    test_targets = set()
    out = run_buck(buck, 'targets', '--json', *targets)
    for info in json.loads(out):
        if info['buck.type'].endswith('_test'):
            test_targets.add(
                '//' + info['buck.base_path'] + ':' + info['name'])
    if not test_targets:
        raise Exception('no test targets')

    # Now run a build to populate the cache.
    logging.info('Running a build to populate the cache')
    run_buck(buck, 'build', *test_targets)

    # Empty the build output.
    logging.info('Removing output from build')
    clear_output()

    # Now run the test
    run_buck(buck, 'test', *test_targets)


def main(argv):
    parser = argparse.ArgumentParser()
    parser.add_argument('--buck', default='buck')
    parser.add_argument('command', choices=('build', 'test'))
    parser.add_argument('targets', metavar='target', nargs='+')
    args = parser.parse_args(argv[1:])

    logging.basicConfig(
        level=logging.INFO,
        format='%(asctime)s %(message)s',
        datefmt='%m/%d/%Y %I:%M:%S %p')

    # Resolve any aliases in the top-level targets.
    out = run_buck(args.buck, 'targets', *args.targets)
    targets = set(out.splitlines())

    # Clear the cache and output directories to start with a clean slate.
    logging.info('Clearing output and cache')
    run_buck(args.buck, 'clean')
    clear_output()
    clear_cache()

    # Run the subcommand
    if args.command == 'build':
        build(args.buck, targets)
    elif args.command == 'test':
        test(args.buck, targets)
    else:
        raise Exception('unknown command: ' + args.command)


sys.exit(main(sys.argv))
