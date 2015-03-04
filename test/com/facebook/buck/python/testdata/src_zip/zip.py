import os.path
import sys
import zipfile


def main():
    root_dir, output_file = sys.argv[1:3]
    input_files = sys.argv[3:]

    f = zipfile.ZipFile(output_file, 'w')
    for input_file in input_files:
        f.write(input_file, os.path.join(root_dir, os.path.basename(input_file)))
    f.close()

if __name__ == '__main__':
    main()
