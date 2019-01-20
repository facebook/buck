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
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <limits.h>
#include <unistd.h>
#include <poll.h>
#include <sys/time.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>


#include "constants.h"


// Return 0 on success
static int parse_args(int num_args, char** args, char** out_ip_str, uint16_t* out_port, uint32_t* out_nonce) {
  if (num_args != 3) {
    fprintf(stderr, "usage: multi-receive-file IP PORT NONCE\n");
    return -1;
  }

  *out_ip_str = args[0];

  char* endptr;

  const char* port_str = args[1];
  long port = strtol(port_str, &endptr, 10);
  if (*port_str == '\0' || *endptr != '\0' || port <= 0 || port > USHRT_MAX) {
    fprintf(stderr, "Invalid port: %s\n", port_str);
    return -1;
  }

  const char* nonce_str = args[2];
  long nonce = strtol(nonce_str, &endptr, 10);
  if (*nonce_str == '\0' || *endptr != '\0' || nonce <= 0 || nonce > INT_MAX) {
    fprintf(stderr, "Invalid nonce: %s\n", nonce_str);
    return -1;
  }

  *out_port = (uint16_t)port;
  *out_nonce = (uint32_t)nonce;
  return 0;
}

// Returns 0 on success.
static int set_socket_timeout(int sock) {
  struct timeval timeout;
  timeout.tv_sec = RECEIVE_TIMEOUT_SEC;
  timeout.tv_usec = 0;
  int ret = setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
  if (ret != 0) {
    perror("setsockopt(SO_RCVTIMEO)");
    return ret;
  }
  return 0;
}

// On success, returns 0 and sets *out_listen_socket to the socket fd.
static int bind_socket(uint16_t port, int* out_listen_socket) {
  int sock = socket(AF_INET, SOCK_STREAM, 0);
  if (sock < 0) {
    perror("socket");
    return sock;
  }

  int ret;

  int one = 1;
  ret = setsockopt(sock, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
  if (ret != 0) {
    perror("setsockopt(SO_REUSEADDR)");
    goto error;
  }

  struct sockaddr_in addr;
  memset(&addr, 0, sizeof(addr));
  addr.sin_family = AF_INET;
  addr.sin_port = htons(port);
  addr.sin_addr.s_addr = htonl(INADDR_ANY);

  ret = bind(sock, (struct sockaddr*)&addr, sizeof(addr));
  if (ret != 0) {
    perror("bind");
    goto error;
  }

  ret = listen(sock, 1);
  if (ret != 0) {
    perror("listen");
    goto error;
  }

  *out_listen_socket = sock;
  return 0;

error:
  close(sock);
  return ret;
}

// On success, returns 0 and sets *out_client_socket to the socket fd.
static int get_client(int listen_socket, int* out_client_socket) {
  struct pollfd pfd;

  pfd.fd = listen_socket;
  pfd.events = POLLIN | POLLERR;

  int ret = poll(&pfd, 1, CONNECT_TIMEOUT_MS);
  if (ret < 0) {
    // Don't bother trying to recover from EINTR, etc. yet.
    perror("poll(listenfd)");
    return ret;
  }
  if (ret == 0) {
    fprintf(stderr, "poll timed out for listen");
    return -1;
  }
  if (pfd.revents != POLLIN) {
    fprintf(stderr, "poll gave unexpected revents for listen %hd", pfd.revents);
    return -1;
  }

  int client_socket = accept(listen_socket, NULL, NULL);
  if (client_socket < 0) {
    perror("accept");
    return client_socket;
  }

  // Set this here so it will be closed if the timeout has an error.
  *out_client_socket = client_socket;

  ret = set_socket_timeout(client_socket);
  if (ret != 0) {
    return -1;
  }

  return 0;
}

// On success, returns 0 and sets *out_client_socket to the socket fd.
static int connect_client(const char* ip_str, uint16_t port, int* out_client_socket) {
  int ret;
  struct sockaddr_in addr;
  memset(&addr, 0, sizeof(addr));
  addr.sin_family = AF_INET;
  addr.sin_port = htons(port);
  ret = inet_aton(ip_str, &addr.sin_addr);
  if (ret != 1) {
    fprintf(stderr, "Invalid IP address: %s\n", ip_str);
    return -1;
  }

  int sock = socket(AF_INET, SOCK_STREAM, 0);
  if (sock < 0) {
    perror("socket");
    return -1;
  }

  // Set this here so it will be closed if we have an error.
  *out_client_socket = sock;

  ret = connect(sock, (struct sockaddr*)&addr, sizeof(addr));
  if (ret < 0) {
    perror("connect");
    return -1;
  }

  ret = set_socket_timeout(sock);
  if (ret != 0) {
    return -1;
  }

  return 0;
}

// On success returns 0 and popultes text_key_buffer, which must be at least
// TEXT_SECRET_KEY_SIZE+1 bytes large.
static int create_and_send_session_key(char* text_key_buffer) {
  FILE* handle = fopen("/dev/urandom", "rb");
  if (handle == NULL) {
    perror("fopen(urandom)");
    return -1;
  }

  uint8_t binary_buffer[BINARY_SECRET_KEY_SIZE];
  size_t got = fread(binary_buffer, 1, BINARY_SECRET_KEY_SIZE, handle);
  if (got != BINARY_SECRET_KEY_SIZE) {
    perror("fread(urandom)");
  }
  fclose(handle);
  if (got != BINARY_SECRET_KEY_SIZE) {
    return -1;
  }

  for (int i = 0; i < BINARY_SECRET_KEY_SIZE; i++) {
    sprintf(&text_key_buffer[2*i], "%02X", binary_buffer[i]);
  }

  printf("%s\n", text_key_buffer);
  fflush(stdout);

  return 0;
}

// Tries to read count bytes, looping over read() until a failure.
// Returns the number of bytes read on success or EOF.
// Returns < 0 on failure.
static ssize_t read_all(int fd, void* buf, size_t count) {
  ssize_t total_received = 0;
  while (count > 0) {
    ssize_t got = read(fd, buf, count);
    if (got < 0) {
      // Could check for EINTR here.
      perror("read");
      return -1;
    }
    if (got == 0) {
      return total_received;
    }
    total_received += got;
    buf += got;
    count -= got;
  }
  return total_received;
}

// Returns 0 on success.
static int receive_and_validate_session_key(char* expected_key, int sock) {
  char received_key[TEXT_SECRET_KEY_SIZE+1];
  received_key[TEXT_SECRET_KEY_SIZE] = '\0';
  ssize_t got = read_all(sock, received_key, TEXT_SECRET_KEY_SIZE);
  if (got < 0) {
    return -1;
  }
  if (got < TEXT_SECRET_KEY_SIZE) {
    fprintf(stderr, "Did not receive full-length key.\n");
    return -1;
  }
  if (memcmp(expected_key, received_key, TEXT_SECRET_KEY_SIZE) != 0) {
    fprintf(stderr, "Received incorrect secret key.\n");
    return -1;
  }
  return 0;
}

// Returns 0 on success.
static int raw_receive_file(const char* path, int expected_size, int sock) {
  int ret;

  const char* slash = strrchr(path, '/');
  if (slash == NULL) {
    fprintf(stderr, "Could not find slash in file name.\n");
    return -1;
  }
  char tempfile[PATH_MAX];
  ret = snprintf(tempfile, sizeof(tempfile), "%.*s%s%s-XXXXXX", (uint32_t)(slash-path+1), path, TEMP_PREFIX, slash+1);
  if (ret <= 0 || ret >= sizeof(tempfile)) {
    fprintf(stderr, "temp file name snprintf failed: %d\n", ret);
    return -1;
  }

  int temp_fd = -1;

  // Maybe set umask here?
  ret = mkstemp(tempfile);
  if (ret < 0) {
    perror("mkstemp");
    return ret;
  }
  temp_fd = ret;

  ssize_t total_size = 0;
  for (;;) {
    // TODO: enforce global timeout
    const int buffer_size = 128 * 1024;
    uint8_t buffer[buffer_size];
    int remaining = expected_size - total_size;
    if (remaining == 0) {
      break;
    }
    int want = buffer_size;
    if (want > remaining) {
      want = remaining;
    }
    ssize_t got = read(sock, buffer, want);
    if (got < 0) {
      perror("read(file)");
      goto error;
    }
    if (got == 0) {
      break;
    }
    ssize_t wrote = write(temp_fd, buffer, got);
    if (wrote != got) {
      perror("write");
      goto error;
    }
    total_size += wrote;
  }

  close(temp_fd);
  temp_fd = -1;

  if (total_size != expected_size) {
    fprintf(stderr, "Received only %d of %d bytes.", (int)total_size, (int)expected_size);
    goto error;
  }

  ret = rename(tempfile, path);
  if (ret != 0) {
    perror("rename");
    goto error;
  }

  return 0;

error:
  if (temp_fd >= 0) {
    close(temp_fd);
  }
  return -1;
}

// Returns 0 on success.
static int multi_receive_file_from_socket(int sock) {
  for (;;) {
    ssize_t got;
    char* endptr;

    char header_size_buf[5];
    got = read_all(sock, header_size_buf, sizeof(header_size_buf));
    if (got < 0) {
      return -1;
    }
    if (got < sizeof(header_size_buf)) {
      fprintf(stderr, "Did not receive full-length header size.\n");
      return -1;
    }
    if (header_size_buf[4] != ' ') {
      fprintf(stderr, "Header size not followed by space.\n");
      return -1;
    }
    header_size_buf[4] = '\0';

    long header_size = strtol(header_size_buf, &endptr, 16);
    if (*header_size_buf == '\0' || *endptr != '\0' || header_size <= 0 || header_size > HEADER_SIZE_MAX) {
      fprintf(stderr, "Invalid header size: %s\n", header_size_buf);
      return -1;
    }

    char header_buf[HEADER_SIZE_MAX];
    got = read_all(sock, header_buf, header_size);
    if (got < 0) {
      return -1;
    }
    if (got < header_size) {
      fprintf(stderr, "Did not receive full-length header.\n");
      return -1;
    }

    char* space = strchr(header_buf, ' ');
    if (space == NULL) {
      fprintf(stderr, "No space in header.\n");
      return -1;
    }
    *space = '\0';

    long file_size = strtol(header_buf, &endptr, 10);
    if (*header_buf == '\0' || *endptr != '\0' || file_size < 0 || file_size > INT_MAX) {
      fprintf(stderr, "Invalid file size: %s\n", header_buf);
      return -1;
    }

    char* file_name = space + 1;
    char* newline = strchr(file_name, '\n');
    if (newline == NULL) {
      fprintf(stderr, "No newline in header.\n");
      return -1;
    }
    *newline = '\0';

    if (file_size == 0 && strcmp(file_name, "--continue") == 0) {
      continue;
    }
    if (file_size == 0 && strcmp(file_name, "--complete") == 0) {
      break;
    }

    int ret = raw_receive_file(file_name, file_size, sock);
    if (ret != 0) {
      return -1;
    }
  }

  return 0;
}

// Return socket fd on success, -1 on failure.
static int accept_authentic_connection_from_client(uint16_t port) {
  int listen_socket = -1;
  int client_socket = -1;

  if (bind_socket(port, &listen_socket) != 0) {
    goto fail1;
  }

  char secret_key[TEXT_SECRET_KEY_SIZE+1];
  if (create_and_send_session_key(secret_key) != 0) {
    goto fail1;
  }

  printf("z1\n");
  fflush(stdout);

  if (get_client(listen_socket, &client_socket) != 0) {
    goto fail1;
  }

  close(listen_socket);
  listen_socket = -1;

  if (receive_and_validate_session_key(secret_key, client_socket) != 0) {
    goto fail1;
  }

  return client_socket;

fail1:
  if (client_socket >= 0) {
    close(client_socket);
  }
  if (listen_socket >= 0) {
    close(listen_socket);
  }
  return -1;
}

// Returns an appropriate process exit code.
static int multi_receive_file_from_server(const char* ip_str, uint16_t port, uint32_t nonce) {
  // Send a byte to trigger the installer to accept our connection.
  printf("\n");
  fflush(stdout);

  int client_socket = -1;
  if (connect_client(ip_str, port, &client_socket) != 0) {
    goto fail1;
  }

  uint8_t nonce_buffer[4];
  nonce_buffer[0] = nonce >> 24;
  nonce_buffer[1] = nonce >> 16;
  nonce_buffer[2] = nonce >> 8;
  nonce_buffer[3] = nonce;
  ssize_t wrote = write(client_socket, nonce_buffer, sizeof(nonce_buffer));
  if (wrote != sizeof(nonce_buffer)) {
    perror("write(nonce)");
    goto fail1;
  }

  int ret = multi_receive_file_from_socket(client_socket);
  if (ret != 0) {
    goto fail1;
  }

  close(client_socket);

  return 0;

fail1:
  if (client_socket >= 0) {
    close(client_socket);
  }
  return 1;
}

int do_multi_receive_file(int num_args, char** args) {
  char* ip_str;
  uint16_t port;
  uint32_t nonce;
  if (parse_args(num_args, args, &ip_str, &port, &nonce) != 0) {
    return 1;
  }

  if (strcmp(ip_str, "-") == 0) {
    int socket = accept_authentic_connection_from_client(port);
    if (socket < 0) {
      return 1;
    }
    int ret = multi_receive_file_from_socket(socket);
    close(socket);
    if (ret != 0) {
      return 1;
    }
    return 0;
  } else {
    return multi_receive_file_from_server(ip_str, port, nonce);
  }
}

