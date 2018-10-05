agent.apk is produced from the Buck build rule
//android/com/facebook/buck/android/agent:agent
Be sure to rebuild it and check in the apk if you change the code.
To rebuild apk:buck build //android/com/facebook/buck/android/agent:agent --out assets/android/agent.apk
If the wire protocol changes at all, increment the version code
in the manifest and AgentUtil to force Buck to reinstall it.
