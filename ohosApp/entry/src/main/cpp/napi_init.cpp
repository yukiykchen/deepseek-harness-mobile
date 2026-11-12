#include "napi/native_api.h"
#include "libshared_api.h"
#include "dsh_kuikly_log.h"
#include <atomic>
#include <hilog/log.h>
#include <string>
#include <vector>

static constexpr unsigned int DSH_LOG_DOMAIN = 0x0D51;
static napi_threadsafe_function g_mainTsfn = nullptr;

static std::string NapiToUtf8(napi_env env, napi_value value) {
    size_t length = 0;
    napi_get_value_string_utf8(env, value, nullptr, 0, &length);
    if (length == 0) {
        return std::string();
    }
    std::vector<char> buf(length + 1);
    napi_get_value_string_utf8(env, value, buf.data(), buf.size(), &length);
    return std::string(buf.data(), length);
}

static void DshKuiklyLogAdapter(int logLevel, const char *tag, const char *message) {
    (void)logLevel;
    (void)tag;
    (void)message;
}

static void CallJsOnMain(napi_env env, napi_value jsCallback, void *context, void *data) {
    (void)context;
    auto *payload = static_cast<std::string *>(data);
    if (env == nullptr || payload == nullptr) {
        delete payload;
        return;
    }
    if (jsCallback != nullptr) {
        napi_value str = nullptr;
        napi_create_string_utf8(env, payload->c_str(), payload->size(), &str);
        napi_value undefined = nullptr;
        napi_get_undefined(env, &undefined);
        napi_value args[1] = {str};
        napi_call_function(env, undefined, jsCallback, 1, args, nullptr);
    }
    delete payload;
}

static napi_value SetMainHandler(napi_env env, napi_callback_info info) {
    napi_value undefined = nullptr;
    napi_get_undefined(env, &undefined);
    if (g_mainTsfn != nullptr) {
        return undefined;
    }
    size_t argc = 1;
    napi_value jsFunc = nullptr;
    napi_get_cb_info(env, info, &argc, &jsFunc, nullptr, nullptr);
    if (argc < 1 || jsFunc == nullptr) {
        return undefined;
    }
    napi_value resourceName = nullptr;
    napi_create_string_utf8(env, "dshPostToMain", NAPI_AUTO_LENGTH, &resourceName);
    napi_status status = napi_create_threadsafe_function(env, jsFunc, nullptr, resourceName, 0, 1, nullptr, nullptr,
                                                         nullptr, CallJsOnMain, &g_mainTsfn);
    if (status != napi_ok) {
        OH_LOG_Print(LOG_APP, LOG_ERROR, DSH_LOG_DOMAIN, "DshOhos", "napi_create_threadsafe_function failed %{public}d",
                     static_cast<int>(status));
        g_mainTsfn = nullptr;
    }
    return undefined;
}

static napi_value PostToMain(napi_env env, napi_callback_info info) {
    napi_value undefined = nullptr;
    napi_get_undefined(env, &undefined);
    if (g_mainTsfn == nullptr) {
        return undefined;
    }
    size_t argc = 1;
    napi_value argv[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
    if (argc < 1 || argv[0] == nullptr) {
        return undefined;
    }
    auto *payload = new std::string(NapiToUtf8(env, argv[0]));
    napi_acquire_threadsafe_function(g_mainTsfn);
    napi_status posted = napi_call_threadsafe_function(g_mainTsfn, payload, napi_tsfn_nonblocking);
    napi_release_threadsafe_function(g_mainTsfn, napi_tsfn_release);
    if (posted != napi_ok) {
        delete payload;
    }
    return undefined;
}

static napi_value InitKuikly(napi_env env, napi_callback_info info) {
    (void)info;
    static std::atomic<bool> logRegistered{false};
    if (!logRegistered.exchange(true)) {
        KRRegisterLogAdapter(&DshKuiklyLogAdapter);
    }
    auto api = libshared_symbols();
    int handler = api->kotlin.root.initKuikly();
    napi_value result;
    napi_create_int32(env, handler, &result);
    return result;
}

EXTERN_C_START
static napi_value Init(napi_env env, napi_value exports) {
    napi_property_descriptor desc[] = {
        {"initKuikly", nullptr, InitKuikly, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"setMainHandler", nullptr, SetMainHandler, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"postToMain", nullptr, PostToMain, nullptr, nullptr, nullptr, napi_default, nullptr},
    };
    napi_define_properties(env, exports, sizeof(desc) / sizeof(desc[0]), desc);
    return exports;
}
EXTERN_C_END

static napi_module demoModule = {
    .nm_version = 1,
    .nm_flags = 0,
    .nm_filename = nullptr,
    .nm_register_func = Init,
    .nm_modname = "entry",
    .nm_priv = ((void *)0),
    .reserved = {0},
};

extern "C" __attribute__((constructor)) void RegisterEntryModule(void) { napi_module_register(&demoModule); }
