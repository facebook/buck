import os
import sys
import subprocess
import uuid
import re
import hashlib
import shutil
import mmap


def parse_args():
    import argparse
    description = """Replaces UUID of un-sanitized Mach O binary and dSYM files so lldb
    would pick them up correctly, making Xcode able to set and resolve breakpoints.
    This script will change the UUID and override the provided bundle and dSYM files."""
    parser = argparse.ArgumentParser(description=description)
    parser.add_argument(
        'app_bundle_path',
        help='Path to .app bundle, e.g. /path/to/appname.app')
    parser.add_argument(
        'dsym_path',
        help='Path to .dSYM bundle, e.g. /path/to/appname.dSYM')
    parser.add_argument(
        'binary_name',
        help='Name of the binary inside app bundle (and of the dwarf file inside dSYM bundle)')
    parser.add_argument(
        '--verbose',
        help='Verbose mode',
        action='store_true',
        default=False)
    return parser.parse_args()


def get_DWARF_file_path(dsym_path, binary_name):
    return os.path.join(dsym_path, 'Contents', 'Resources', 'DWARF', binary_name)


def get_UUIDs_for_binary_at_path(path, verbose):
    process = subprocess.Popen(['dwarfdump', '--uuid', path], stdout=subprocess.PIPE)
    out, err = process.communicate()
    exitCode = process.wait()
    if exitCode != 0:
        print("Unable to detect UUIDs for binary at " + path)
        sys.exit(exitCode)
    UUIDs = re.findall(
        r'[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}', out)
    UUIDs = [uuid.UUID(elem) for elem in UUIDs]
    if verbose:
        print('Detected UUIDs: ' + str(UUIDs))
    return UUIDs


def get_new_UUIDs_for_old_UUIDs(oldUUIDs, verbose):
    newUUIDs = [uuid.UUID(hashlib.sha1(oldUUID.hex).hexdigest()[0:32]) for oldUUID in oldUUIDs]
    if verbose:
        print('Replacement UUIDs: ' + str(newUUIDs))
    return newUUIDs


def get_UUID_replacement_map_for_binary_at_path(binary_path, verbose):
    old_uuids = get_UUIDs_for_binary_at_path(binary_path, verbose)
    new_uuids = get_new_UUIDs_for_old_UUIDs(old_uuids, verbose)
    uuids_map = dict(zip(old_uuids, new_uuids))
    return uuids_map


def update_UUID_in_file(file, old_uuid_bytes, new_uuid_bytes, verbose):
    mm = mmap.mmap(file.fileno(), 0)
    location = mm.find(old_uuid_bytes)
    if location == -1:
        sys.exit('Cannot find UUID in file: ' + file_path)
    if verbose:
        print("Found UUID at " + str(location))
    mm.seek(location)
    mm.write(new_uuid_bytes)
    mm.close()


def apply_UUIDs_map(binary_path, dwarffile_path, uuids_map, verbose):
    tmp_binary_path = binary_path + '_tmp'
    tmp_dwarffile_path = dwarffile_path + '_tmp'
    shutil.copy(binary_path, tmp_binary_path)
    shutil.copy(dwarffile_path, tmp_dwarffile_path)
    binary_file = open(tmp_binary_path, "r+b")
    dwarf_file = open(tmp_dwarffile_path, "r+b")
    if verbose:
        print('Temporary binary path: ' + tmp_binary_path)
        print('Temporary dwarf  path: ' + tmp_dwarffile_path)
    for old_uuid, new_uuid in uuids_map.iteritems():
        update_UUID_in_file(binary_file, old_uuid.bytes, new_uuid.bytes, verbose)
        update_UUID_in_file(dwarf_file, old_uuid.bytes, new_uuid.bytes, verbose)
    binary_file.close()
    dwarf_file.close()
    check_UUIDs_have_been_replaced(tmp_binary_path, tmp_dwarffile_path, uuids_map, verbose)
    os.remove(binary_path)
    os.rename(tmp_binary_path, binary_path)
    os.remove(dwarffile_path)
    os.rename(tmp_dwarffile_path, dwarffile_path)
    return


def check_UUIDs_have_been_replaced(binary_path, dwarffile_path, uuids_map, verbose):
    if verbose:
        print('Confirming UUIDs have been correctly replaced...')
    current_uuids = set(get_UUIDs_for_binary_at_path(binary_path, verbose))
    expected_uuids = set(uuids_map.values())
    if current_uuids != expected_uuids:
        sys.exit("UUID replacement failed")
    else:
        if verbose:
            print('UUID replacement succeeded')


def replace_UUIDs(app_bundle_path, dsym_path, binary_name, verbose):
    binary_path = os.path.join(app_bundle_path, binary_name)
    dwarffile_path = get_DWARF_file_path(dsym_path, binary_name)
    uuids_map = get_UUID_replacement_map_for_binary_at_path(binary_path, verbose)
    apply_UUIDs_map(binary_path, dwarffile_path, uuids_map, verbose)
    return


def main():
    args = parse_args()
    if not os.path.exists(args.app_bundle_path):
        sys.exit('Cannot find app bundle at ' + args.app_bundle_path)
    if not os.path.exists(args.dsym_path):
        sys.exit('Cannot find dSYM bundle at ' + args.dsym_path)
    binary_path = os.path.join(args.app_bundle_path, args.binary_name)
    if not os.path.exists(binary_path):
        sys.exit('Cannot find binary file at ' + binary_path)
    dwarffile_path = get_DWARF_file_path(args.dsym_path, args.binary_name)
    if not os.path.exists(dwarffile_path):
        sys.exit('Cannot find .o file at ' + dwarffile_path)
    replace_UUIDs(args.app_bundle_path,
                  args.dsym_path,
                  args.binary_name,
                  args.verbose)


if __name__ == '__main__':
    main()
