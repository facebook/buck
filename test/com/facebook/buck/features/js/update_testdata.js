/*
 * Copyright 2018-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
/**
 * Automate updating 'snapshots' for JS rules integration tests. You can run
 * it like:
 *
 *     buck test //test/com/facebook/buck/features/js:js 2>&1 | node test/com/facebook/buck/js/update_testdata.js
 *
 * Several passes may be necessary until it say there are no more failures.
 */

'use strict';

const path = require('path');
const fs = require('fs');
const {Writable} = require('stream');

(async function main() {
  const input = (await getInput()).toString('utf8');
  const re = /\nFAILURE [a-zA-Z.]+ [a-zA-Z]+: In ([0-9a-zA-Z_#,./-]+)\., expected content of ([\s\S]*?) to match that of ([\s\S]*?). expected:</g;
  let match = re.exec(input);
  if (match == null) {
    console.warn('No more failures to process.');
    return;
  }
  while (match != null) {
    const localFilePath = match[1] + '.expected';
    const filePath = path.resolve(__dirname, 'testdata', 'js_rules', localFilePath);
    const supposedContent = match[2];
    const expectedContent = match[3];
    const actualContent = fs.readFileSync(filePath, 'utf8');
    if (supposedContent !== actualContent) {
      throw new Error(`Content doesn't match for file '${filePath}'`);
    }
    fs.writeFileSync(filePath, expectedContent, 'utf8');
    match = re.exec(input);
  }
})().catch(error => process.nextTick(() => { throw error; }));

function getInput() {
  return new Promise((resolve) => {
    const chunks = [];
    process.stdin.pipe(new Writable({
      write(chunk, encoding, callback) {
        chunks.push(chunk);
        callback();
      }
    })).on('finish', () => {
      resolve(Buffer.concat(chunks));
    }).on('error', error => {
      reject(error)
    });
  });
}
