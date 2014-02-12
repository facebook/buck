agent.apk is produced from the Buck build rule
//src/com/facebook/buck/android/agent:agent
Be sure to rebuild it and check in the apk if you change the code.
If the wire protocol changes at all, increment the version code
in the manifest and AgentUtil to force Buck to reinstall it.
