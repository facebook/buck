PEX
===
.. image:: https://travis-ci.org/pantsbuild/pex.svg?branch=master
    :target: https://travis-ci.org/pantsbuild/pex

pex is a library for generating .pex (Python EXecutable) files which are
executable Python environments in the spirit of `virtualenvs <http://virtualenv.org>`_.
pex is an expansion upon the ideas outlined in
`PEP 441 <http://legacy.python.org/dev/peps/pep-0441/>`_
and makes the deployment of Python applications as simple as ``cp``.  pex files may even
include multiple platform-specific Python distributions, meaning that a single pex file
can be portable across Linux and OS X.

pex files can be built using the ``pex`` tool.  Build systems such as `Pants
<http://pantsbuild.github.io/>`_ and `Buck <http://facebook.github.io/buck/>`_ also
support building .pex files directly.

Still unsure about what pex does or how it works?  Watch this quick lightning
talk: `WTF is PEX? <http://www.youtube.com/watch?v=NmpnGhRwsu0>`_.

pex is licensed under the Apache2 license.


Installation
============

To install pex, simply

.. code-block:: bash

    $ pip install pex

You can also build pex in a git clone using tox:

.. code-block:: bash

    $ tox -e py27-package
    $ cp dist/pex ~/bin

This builds a pex binary in ``dist/pex`` that can be copied onto your ``$PATH``.
The advantage to this approach is that it keeps your Python environment as empty as
possible and is more in-line with what pex does philosophically.


Simple Examples
===============

Launch an interpreter with ``requests``, ``flask`` and ``psutil`` in the environment:

.. code-block:: bash

    $ pex requests flask 'psutil>2,<3'

Or instead freeze your current virtualenv via requirements.txt and execute it anywhere:

.. code-block:: bash

    $ pex -r <(pip freeze) -o my_virtualenv.pex
    $ deactivate
    $ ./my_virtualenv.pex

Run webserver.py in an environment containing ``flask`` as a quick way to experiment:

.. code-block:: bash

    $ pex flask -- webserver.py

Launch Sphinx in an ephemeral pex environment using the Sphinx entry point ``sphinx:main``:

.. code-block:: bash

    $ pex sphinx -e sphinx:main -- --help

Build a standalone pex binary into ``pex.pex`` using the ``pex`` console_scripts entry point:

.. code-block:: bash

    $ pex pex -c pex -o pex.pex

You can also build pex files that use a specific interpreter type:

.. code-block:: bash

    $ pex pex -c pex --python=pypy -o pypy-pex.pex

Most pex options compose well with one another, so the above commands can be
mixed and matched.  For a full list of options, just type ``pex --help``.


Integrating pex into your workflow
==================================

If you use tox (and you should!), a simple way to integrate pex into your
workflow is to add a packaging test environment to your ``tox.ini``:

.. code-block:: ini

    [testenv:package]
    deps = pex
    commands = pex . -o dist/app.pex

Then ``tox -e package`` will produce a relocateable copy of your application
that you can copy to staging or production environments.


Documentation
=============

More documentation about pex, building .pex files, and how .pex files work
is available at http://pex.rtfd.org.


Development
===========

pex uses `tox <https://testrun.org/tox/latest/>`_ for test and development automation.  To run
the test suite, just invoke tox:

.. code-block:: bash

    $ tox

If you don't have tox, you can generate a pex of tox:

.. code-block::

    $ pex tox -c tox -o ~/bin/tox


Contributing
============

To contribute, follow these instructions: http://pantsbuild.github.io/howto_contribute.html
