This directory contains the templates for Buck's HTML documentation,
as well as the scripts to publish said documentation.

Editing Documentation
=====================
Buck documentation is written using
[Closure Templates](https://developers.google.com/closure/templates/).
Documentation can be developed locally by running the following command:

    ./docs/soyweb-local.sh

and then navigating to <http://localhost:9811/> in the browser. The typical
edit/refresh style of web development applies for editing these docs.
Ideally, changes to Buck code will include updates to these docs in the same
commit so that the relationship between the code and documentation changes is
clear.

Note that the edit/refresh cycle of local documentation development is made
possible via [plovr](http://plovr.com/soyweb.html).


Publishing Documentation
========================

This documentation is hosted publicly at <http://facebook.github.com/buck/>
using [GitHub Pages](http://pages.github.com/).
Therefore, to publish this documentation, you must commit it on the
`gh-pages` branch of the GitHub repository by running:

    ./docs/soyweb-prod.sh &
    ./docs/publish.sh
    fuser -k -n tcp 9814

Creating a New Article
======================

Create a file and seed it with the following content:

    {namespace buck.ADD_YOUR_PAGE_NAME}

    /***/
    {template .soyweb}
      {call buck.page}
        {param title: 'ADD_YOUR_TITLE' /}
        {param content}

    ADD_YOUR_CONTENT_HERE

        {/param}
      {/call}
    {/template}

Update the three placeholders in all caps and you should be good to go!
