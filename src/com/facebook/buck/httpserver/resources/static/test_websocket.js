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
function reportError(message) {
  alert(message);
}


if (typeof WebSocket === 'undefined') {
  reportError('Your user agent does not support WebSockets. Lame.');
}


function establishConnection() {
  var currentWebSocket = null;

  var dataToSend = [];

  var send = function(data) {
    if (currentWebSocket) {
      currentWebSocket.send(JSON.stringify(data));
    } else {
      console.log('Currently disconnected. Queueing request: ' + JSON.stringify(data));
      dataToSend.push(data);
    }
  };

  var connect = function() {
    currentWebSocket = new WebSocket('ws://localhost:' + location.port + '/ws/build');

    currentWebSocket.onopen = function() {
      console.log('Connected!');

      while (dataToSend.length) {
        var data = dataToSend.shift();
        send(data);
      }
    };

    currentWebSocket.onmessage = function(e) {
      var data = JSON.parse(e.data);
      console.log(data);
    };

    currentWebSocket.onclose = function() {
      console.log('Disconnected. Trying to reconnect...');
      currentWebSocket = null;

      // Wait 1s before attempting to reconnect. If the connection fails,
      // this method will be invoked again, scheduling another attempt.
      setTimeout(connect, 1000);
    };
  };

  // This will get queued so it is sent in onopen().
  connect();
};

establishConnection();
