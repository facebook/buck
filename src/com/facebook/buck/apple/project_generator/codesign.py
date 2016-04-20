import os
import sys
import subprocess
import uuid
import re
import hashlib
import shutil
import mmap
import tempfile

AUTHORITY_MARKER = 'Authority='


class BuckCodesign(object):
    def __init__(self, codesign_tool_path, app_bundle_path):
        self.codesign_tool_path = codesign_tool_path
        self.app_bundle_path = app_bundle_path

    def get_output_for_command(self, command_as_array, fail_automatically=True, merge_stderr=True):
        err_output = None
        if merge_stderr:
            err_output = subprocess.STDOUT
        process = subprocess.Popen(command_as_array, stdout=subprocess.PIPE, stderr=err_output)
        out, err = process.communicate()
        exitCode = process.wait()
        if exitCode != 0 and fail_automatically:
            print('Command `' + ' '.join(command_as_array) + '` returned ' + str(exitCode))
            print('Output:\n' + out)
            sys.exit(exitCode)
        return out

    def check_bundle_was_previously_signed(self):
        print("Checking previous signature of the bundle")
        out = self.get_output_for_command([self.codesign_tool_path,
                                          '--verify',
                                          self.app_bundle_path],
                                          fail_automatically=False)
        if 'code object is not signed at all' in out:
            print("Bundle was not signed, doing nothing")
            sys.exit(0)

    def prepare_entitlements(self):
        print("Getting entitlements from " + self.app_bundle_path)
        entitlements = self.get_output_for_command([self.codesign_tool_path,
                                                   '-d',
                                                   '--entitlements',
                                                   '-',
                                                   self.app_bundle_path],
                                                   merge_stderr=False)
        self.entitlements_file = tempfile.NamedTemporaryFile(mode='w+b')
        self.entitlements_file.write(entitlements)
        self.entitlements_file.seek(0)

    def prepare_identity(self):
        signature = self.get_output_for_command([self.codesign_tool_path,
                                                 '-vvvv',
                                                 '-d',
                                                 self.app_bundle_path])
        for line in signature.splitlines():
            if line.startswith(AUTHORITY_MARKER):
                self.identity = line[len(AUTHORITY_MARKER):].strip()
                print("Found identity: " + self.identity)
                break
        if not self.identity:
            print("Unable to find identity information from signature:\n" + signature)
            sys.exit(1)

    def prepare_identity_sha(self):
        available_identities = self.get_output_for_command(['security',
                                                            'find-identity',
                                                            '-v',
                                                            '-p',
                                                            'codesigning'])
        print("Available codesigning identities:\n" + available_identities)
        for line in available_identities.splitlines():
            if self.identity in line:
                identity_sha = re.search(r'[0-9A-F]{40}', line)
                if identity_sha:
                    self.identity_sha = identity_sha.group(0)
                    print("Found SHA for identity: " + self.identity_sha)
                    break
        if not self.identity_sha:
            print("Unable to find SHA for identity: " + self.identity)
            sys.exit(1)

    def invoke_sign(self):
        out = self.get_output_for_command([self.codesign_tool_path,
                                           '-s',
                                           self.identity_sha,
                                           '-f',
                                           '--entitlements',
                                           self.entitlements_file.name,
                                           self.app_bundle_path])
        print("Codesign complete: " + out)

    def sign(self):
        self.check_bundle_was_previously_signed()
        self.prepare_entitlements()
        self.prepare_identity()
        self.prepare_identity_sha()
        self.invoke_sign()


def parse_args():
    import argparse
    description = """Performs re-signing of the bundle. Uses original
    entitlements from the bundle as well as embedded provisioning profile."""
    parser = argparse.ArgumentParser(description=description)
    parser.add_argument(
        'codesign_tool_path',
        help='Path to `codesign` tool, e.g. /usr/bin/codesign')
    parser.add_argument(
        'app_bundle_path',
        help='Path to .app bundle to re-sign, e.g. /path/to/appname.app')
    return parser.parse_args()


def main():
    args = parse_args()
    if not os.path.exists(args.app_bundle_path):
        sys.exit('Cannot find app bundle at ' + args.app_bundle_path)
    if not os.path.exists(args.app_bundle_path):
        sys.exit('Cannot find codesign tool at ' + args.codesign_tool_path)
    signer = BuckCodesign(args.codesign_tool_path, args.app_bundle_path)
    signer.sign()


if __name__ == '__main__':
    main()
