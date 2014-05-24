Pex.pex: Usage
==============

[PEX](https://github.com/twitter/commons/blob/master/src/python/twitter/pants/python/README.md) files are single-file lightweight virtual Python environments.

pex.pex is a utility that:
* creates PEX files
* provides a single use run-environment

Installation
------------

See this doc: [[pex.pex.install|pants('src/python/twitter/common/python:pexinstall')]]

Usage
-----
~~~~~~~~~
:::console
Usage: pex.pex [options]

pex.pex builds a PEX (Python Executable) file based on the given specifications: sources, requirements, their dependencies and other options

Options:
  --version             show program's version number and exit
  -h, --help            show this help message and exit
  --pypi, --no-pypi     Whether to use pypi to resolve dependencies; Default:
                        use pypi
  --cache-dir=CACHE_DIR
                        The local cache directory to use for speeding up
                        requirement lookups; Default: ~/.pex/install
  -p PEX_NAME, --pex-name=PEX_NAME
                        The name of the generated .pex file: Omiting this will
                        run PEX immediately and not save it to a file
  -e ENTRY_POINT, --entry-point=ENTRY_POINT
                        The entry point for this pex; Omiting this will enter
                        the python IDLE with sources and requirements
                        available for import
  -r REQUIREMENT, --requirement=REQUIREMENT
                        requirement to be included; may be specified multiple
                        times.
  --repo=PATH           Additional repository path (directory or URL) to look
                        for requirements.
  -s DIR, --source-dir=DIR
                        Source to be packaged; This <DIR> should be a pip-
                        installable project with a setup.py.
  -v, --verbosity       Turn on logging verbosity.
~~~~~~~~~

Use cases
---------

* An isolated python environment containing your requirements and its dependencies for one time use

        :::console
        pex.pex --requirement fabric
        ...
        >>> import fabric
        >>> ^D

* A PEX file encapsulating your requirements and its dependencies for repeated use and sharing

        :::console
        pex.pex --requirement fabric --pex-name my_fabric.pex
        ./my_fabric.pex
        ...
        >>> import fabric
        >>> ^D

* A PEX file encapsulating your requirements intended to be run with a specific entry point

        :::console
        pex.pex --requirement fabric --entry-point fabric.main:main --pex-name my_fabric.pex
        ...
        ./my_fabric.pex -h

        Usage: fab [options] <command>[:arg1,arg2=val2,host=foo,hosts='h1;h2',...] ...
 
        Options:
          -h, --help            show this help message and exit
          -d NAME, --display=NAME
                                print detailed info about command NAME


        PEX_INTERPRETER=1 ./my_fabric.pex
        >>> import fabric
        >>> ^D


Bootstrapping pex.pex
---------------------------

* Download virtualenv and create twitter.common.python virtualenv

        :::console
        curl -O https://pypi.python.org/packages/source/v/virtualenv/virtualenv-1.11.tar.gz
        tar zxf virtualenv-1.11.tar.gz
        python virtualenv-1.11/virtualenv.py twitter.common.python.venv

* Activate and install twitter.common.python

        :::console
        cd twitter.common.python.venv
        source bin/activate
        pip install twitter.common.python

* Use bin/pex to bootstrap itself

        :::console
        pex -r twitter.common.python -e twitter.common.python.bin.pex:main -p pex.pex

* Copy and deactivate virtualenv

        :::console
        cp pex.pex ~/bin/pex
        deactivate

* Use pex utility

        :::console
        # Presumes that ~/bin is on $PATH
        pex -r fabric -e fabric.main:main
