int main(void) {
#if defined(__APPLE__) && defined(__aarch64__) && defined(__TINYC__)
    char buffer[128] = {0};
    __arm64_clear_cache(buffer, buffer + sizeof(buffer));
#endif
    return 0;
}
