#!/usr/bin/env python3
import sys
from distutils.core import setup


setup(
    name='pathlib',
    version=open('VERSION.txt').read().strip(),
    py_modules=['pathlib'],
    license='MIT License',
    description='Object-oriented filesystem paths',
    long_description=open('README.txt').read(),
    author='Antoine Pitrou',
    author_email='solipsis@pitrou.net',
    classifiers=[
        'Development Status :: 5 - Production/Stable',
        'Intended Audience :: Developers',
        'License :: OSI Approved :: MIT License',
        'Operating System :: OS Independent',
        'Programming Language :: Python :: 3',
        'Programming Language :: Python :: 2.6',
        'Programming Language :: Python :: 2.7',
        'Programming Language :: Python :: 3.2',
        'Programming Language :: Python :: 3.3',
        'Programming Language :: Python :: 3.4',
        'Topic :: Software Development :: Libraries',
        'Topic :: System :: Filesystems',
        ],
    download_url='https://pypi.python.org/pypi/pathlib/',
    url='https://pathlib.readthedocs.org/',
)
