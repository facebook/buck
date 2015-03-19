prebuilts(data) ::= <<
<data:prebuilt()>
>>

prebuilt(data) ::= <<
prebuilt_jar(
  name = '<data.name>',
  binary_jar = '<data.binaryJar>',
  <if(data.sourceJar)>source_jar = '<data.sourceJar>',<endif>
  <if(data.deps)>
  deps = [
    <data.deps: {x | '<x>'}; separator=",\n">
  ],<endif>
  <if(data.visibility)>
  visibility = [
    <data.visibility: {x | '<x>'}; separator=",\n">
  ],<endif>

)
<\n>
>>
