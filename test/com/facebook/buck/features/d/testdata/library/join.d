string join(string joiner, string[] parts) {
  if (parts.length == 0) {
    return "";
  }
  string result = parts[0];
  for(auto i = 1; i < parts.length; i++) {
    result ~= joiner;
    result ~= parts[i];
  }
  return result;
}
