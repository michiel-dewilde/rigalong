#include "harness.h"

namespace test {

std::vector<Case>& registry() {
    // Function-local, so registration from static initialisers in other
    // translation units cannot race the vector's own construction.
    static std::vector<Case> cases;
    return cases;
}

static int failuresInCase = 0;

void fail(const char* file, int line, const std::string& what) {
    ++failuresInCase;
    // A relative-looking path, so the output is readable and the editor can
    // still find the file.
    const char* slash = file;
    for (const char* p = file; *p; ++p)
        if (*p == '/' || *p == '\\') slash = p + 1;
    std::printf("      %s:%d  %s\n", slash, line, what.c_str());
}

}  // namespace test

int main(int argc, char** argv) {
    const char* filter = (argc > 1) ? argv[1] : nullptr;
    int ran = 0, failed = 0;

    for (const auto& c : test::registry()) {
        if (filter && std::string(c.name).find(filter) == std::string::npos) continue;
        ++ran;
        test::failuresInCase = 0;
        std::printf("  %-44s", c.name);
        std::fflush(stdout);
        c.fn();
        if (test::failuresInCase == 0) {
            std::printf(" ok\n");
        } else {
            std::printf(" FAILED (%d)\n", test::failuresInCase);
            ++failed;
        }
    }

    std::printf("\n%d case%s, %d failed\n", ran, ran == 1 ? "" : "s", failed);
    return failed == 0 ? 0 : 1;
}
