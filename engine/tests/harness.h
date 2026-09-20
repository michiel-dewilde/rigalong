#pragma once

// A test harness in sixty lines, with no dependency.
//
// The alternative was GoogleTest, and it was rejected on the same grounds as
// everything else in this project: the engine directory exists to prove that a
// piece of this code needs nothing, and a test runner that needs a package
// manager undoes that on the first line. What is here is enough -- named cases,
// assertions that report file and line, a non-zero exit on failure -- and it
// builds with one g++ invocation in about a second.
//
// If the suite ever needs fixtures, parameterisation or death tests, that is
// the moment to reconsider. Not before.

#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

namespace test {

struct Case {
    const char* name;
    void (*fn)();
};

std::vector<Case>& registry();
void fail(const char* file, int line, const std::string& what);

struct Register {
    Register(const char* name, void (*fn)()) { registry().push_back({name, fn}); }
};

// The exact (non-template) overloads win for the types named here; everything
// else arithmetic falls through to the template. Writing bool and double as two
// plain overloads instead made str(int) ambiguous between them, which is a
// compile error in the ASSERTION MACRO -- the one place where an obscure
// diagnostic is least welcome.
inline std::string str(const std::string& v) { return "\"" + v + "\""; }
inline std::string str(const char* v) { return v ? std::string("\"") + v + "\"" : "null"; }
inline std::string str(bool v) { return v ? "true" : "false"; }

template <class T>
inline std::string str(T v) {
    char buf[64];
    std::snprintf(buf, sizeof buf, "%g", static_cast<double>(v));
    return buf;
}

}  // namespace test

#define TEST(name)                                                    \
    static void name();                                               \
    static ::test::Register test_reg_##name(#name, name);             \
    static void name()

#define CHECK(cond)                                                   \
    do {                                                              \
        if (!(cond)) ::test::fail(__FILE__, __LINE__, "CHECK(" #cond ")"); \
    } while (0)

#define CHECK_EQ(a, b)                                                \
    do {                                                              \
        auto va_ = (a);                                               \
        auto vb_ = (b);                                               \
        if (!(va_ == vb_))                                            \
            ::test::fail(__FILE__, __LINE__,                          \
                         std::string(#a " == " #b "  (got ") +        \
                             ::test::str(va_) + ", want " +           \
                             ::test::str(vb_) + ")");                 \
    } while (0)

#define CHECK_NEAR(a, b, eps)                                         \
    do {                                                              \
        double va_ = (a), vb_ = (b);                                  \
        if (!(std::fabs(va_ - vb_) <= (eps)))                         \
            ::test::fail(__FILE__, __LINE__,                          \
                         std::string(#a " ~= " #b "  (got ") +        \
                             ::test::str(va_) + ", want " +           \
                             ::test::str(vb_) + " +/- " +             \
                             ::test::str((double)(eps)) + ")");       \
    } while (0)
