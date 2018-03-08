from py_lib.util import Util


def generate_cpp():
    print('#include <generate_cpp/generated.h>\n')
    print('Generated::Generated() {}\n')
    print('std::string Generated::generated_fcn() {')
    print('return "{}";'.format(Util().name))
    print('}')

if __name__ == "__main__":
    generate_cpp()
