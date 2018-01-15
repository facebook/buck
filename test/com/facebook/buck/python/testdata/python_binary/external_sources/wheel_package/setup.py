from setuptools import setup, find_packages

setup(
    name='wheel_package',
    version='0.0.1',
    description='A sample Python project',
    long_description='A sample Python project',
    url='https://buckbuild.com',
    author='Buck',
    license='Apache License 2.0',
    packages=['wheel_package'],
    # See https://pypi.python.org/pypi?%3Aaction=list_classifiers
    classifiers=[
        'Development Status :: 5 - Production/Stable',
        'Intended Audience :: Developers',
        'Topic :: Software Development :: Build Tools',
        'License :: OSI Approved :: Apache Software License'
        'Programming Language :: Python :: 2',
        'Programming Language :: Python :: 3',
    ],
)
