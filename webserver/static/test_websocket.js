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
    currentWebSocket = new WebSocket('ws://localhost:' + location.port + '/comet/echo');

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
