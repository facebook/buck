#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <limits.h>
#include <unistd.h>
#include <poll.h>
#include <sys/time.h>
#include <sys/socket.h>
#include <netinet/in.h>
//#include <netinet/ip.h>


#include "constants.h"


// Return 0 on success
static int parse_args(int num_args, char** args, uint16_t* out_port, int* out_size, const char** out_path) {
  if (num_args != 3) {
    fprintf(stderr, "usage: receive-file PORT SIZE PATH\n");
    return -1;
  }

  char* endptr;

  const char* port_str = args[0];
  long port = strtol(port_str, &endptr, 10);
  if (*port_str == '\0' || *endptr != '\0' || port <= 0 || port > USHRT_MAX) {
    fprintf(stderr, "Invalid port: %s\n", port_str);
    return -1;
  }

  const char* size_str = args[1];
  long size = strtol(size_str, &endptr, 10);
  if (*size_str == '\0' || *endptr != '\0' || size <= 0 || size > INT_MAX) {
    fprintf(stderr, "Invalid size: %s\n", size_str);
    return -1;
  }

  *out_port = (uint16_t)port;
  *out_size = (int)size;
  *out_path = args[2];
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

  struct timeval timeout;
  timeout.tv_sec = RECEIVE_TIMEOUT_SEC;
  timeout.tv_usec = 0;
  ret = setsockopt(client_socket, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
  if (ret != 0) {
    perror("setsockopt(SO_RCVTIMEO)");
    return ret;
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
    ssize_t got = read(sock, buffer, buffer_size);
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

int do_receive_file(int num_args, char** args) {
  uint16_t port;
  int size;
  const char* path;
  if (parse_args(num_args, args, &port, &size, &path) != 0) {
    return 1;
  }

  int listen_socket = -1;
  int client_socket = -1;

  if (bind_socket(port, &listen_socket) != 0) {
    goto fail1;
  }

  char secret_key[TEXT_SECRET_KEY_SIZE+1];
  if (create_and_send_session_key(secret_key) != 0) {
    goto fail1;
  }

  if (get_client(listen_socket, &client_socket) != 0) {
    goto fail1;
  }

  close(listen_socket);
  listen_socket = -1;

  if (receive_and_validate_session_key(secret_key, client_socket) != 0) {
    goto fail1;
  }

  if (raw_receive_file(path, size, client_socket) != 0) {
    goto fail1;
  }

  return 0;


fail1:
  if (client_socket >= 0) {
    close(client_socket);
  }
  if (listen_socket >= 0) {
    close(listen_socket);
  }
  return 1;
}

