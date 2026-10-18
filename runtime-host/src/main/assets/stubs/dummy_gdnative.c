#define _GNU_SOURCE
#include <gdnative_api_struct.gen.h>
#include <pthread.h>
#include <dlfcn.h>
#include <unistd.h>
#include <sys/mman.h>
#include <link.h>
#include <elf.h>
#include <android/log.h>
#include <string.h>
#include <errno.h>

#define TAG "GodotStub"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static const godot_gdnative_core_api_struct *api = NULL;
static const godot_gdnative_ext_nativescript_api_struct *nativescript_api = NULL;

static int (*real_pthread_mutex_lock)(pthread_mutex_t *) = NULL;
static int (*real_pthread_mutex_trylock)(pthread_mutex_t *) = NULL;

static int hooked_pthread_mutex_lock(pthread_mutex_t *mutex) {
    if (!real_pthread_mutex_lock) {
        real_pthread_mutex_lock = (int (*)(pthread_mutex_t *))dlsym(RTLD_NEXT, "pthread_mutex_lock");
    }
    int res = real_pthread_mutex_lock ? real_pthread_mutex_lock(mutex) : 0;
    if (res == EAGAIN) return 0;
    return res;
}

static int hooked_pthread_mutex_trylock(pthread_mutex_t *mutex) {
    if (!real_pthread_mutex_trylock) {
        real_pthread_mutex_trylock = (int (*)(pthread_mutex_t *))dlsym(RTLD_NEXT, "pthread_mutex_trylock");
    }
    int res = real_pthread_mutex_trylock ? real_pthread_mutex_trylock(mutex) : 0;
    if (res == EAGAIN) return 0;
    return res;
}

static int patch_got_callback(struct dl_phdr_info *info, size_t size, void *data) {
    if (!info->dlpi_name || !info->dlpi_name[0]) return 0;
    const char *name = info->dlpi_name;
    if (!strstr(name, "libgodot") && !strstr(name, "libc++") && !strstr(name, "libOpenSLES")) {
        return 0;
    }

    ElfW(Dyn) *dynamic = NULL;
    for (int i = 0; i < info->dlpi_phnum; i++) {
        if (info->dlpi_phdr[i].p_type == PT_DYNAMIC) {
            dynamic = (ElfW(Dyn) *)(info->dlpi_addr + info->dlpi_phdr[i].p_vaddr);
            break;
        }
    }
    if (!dynamic) return 0;

    ElfW(Rela) *jmprel = NULL;
    size_t pltrelsz = 0;
    ElfW(Sym) *symtab = NULL;
    const char *strtab = NULL;

    for (ElfW(Dyn) *d = dynamic; d->d_tag != DT_NULL; d++) {
        if (d->d_tag == DT_JMPREL) {
            uintptr_t ptr = d->d_un.d_ptr;
            jmprel = (ElfW(Rela) *)(ptr < info->dlpi_addr ? (info->dlpi_addr + ptr) : ptr);
        } else if (d->d_tag == DT_PLTRELSZ) {
            pltrelsz = d->d_un.d_val;
        } else if (d->d_tag == DT_SYMTAB) {
            uintptr_t ptr = d->d_un.d_ptr;
            symtab = (ElfW(Sym) *)(ptr < info->dlpi_addr ? (info->dlpi_addr + ptr) : ptr);
        } else if (d->d_tag == DT_STRTAB) {
            uintptr_t ptr = d->d_un.d_ptr;
            strtab = (const char *)(ptr < info->dlpi_addr ? (info->dlpi_addr + ptr) : ptr);
        }
    }

    if (!jmprel || !symtab || !strtab || pltrelsz == 0) return 0;

    long page_size = sysconf(_SC_PAGESIZE);
    if (page_size <= 0) page_size = 4096;

    size_t count = pltrelsz / sizeof(ElfW(Rela));
    for (size_t i = 0; i < count; i++) {
        uint32_t sym_idx = ELF64_R_SYM(jmprel[i].r_info);
        const char *sym_name = strtab + symtab[sym_idx].st_name;
        if (strcmp(sym_name, "pthread_mutex_lock") == 0) {
            void **got_slot = (void **)(info->dlpi_addr + jmprel[i].r_offset);
            if (*got_slot != (void *)hooked_pthread_mutex_lock) {
                if (!real_pthread_mutex_lock) {
                    real_pthread_mutex_lock = (int (*)(pthread_mutex_t *))*got_slot;
                }
                uintptr_t page = (uintptr_t)got_slot & ~(page_size - 1);
                mprotect((void *)page, page_size, PROT_READ | PROT_WRITE);
                *got_slot = (void *)hooked_pthread_mutex_lock;
                mprotect((void *)page, page_size, PROT_READ);
                LOGI("Patched pthread_mutex_lock GOT slot %p in %s", got_slot, name);
            }
        } else if (strcmp(sym_name, "pthread_mutex_trylock") == 0) {
            void **got_slot = (void **)(info->dlpi_addr + jmprel[i].r_offset);
            if (*got_slot != (void *)hooked_pthread_mutex_trylock) {
                if (!real_pthread_mutex_trylock) {
                    real_pthread_mutex_trylock = (int (*)(pthread_mutex_t *))*got_slot;
                }
                uintptr_t page = (uintptr_t)got_slot & ~(page_size - 1);
                mprotect((void *)page, page_size, PROT_READ | PROT_WRITE);
                *got_slot = (void *)hooked_pthread_mutex_trylock;
                mprotect((void *)page, page_size, PROT_READ);
                LOGI("Patched pthread_mutex_trylock GOT slot %p in %s", got_slot, name);
            }
        }
    }
    return 0;
}

static void install_compat_hooks(void) {
    if (!real_pthread_mutex_lock) {
        real_pthread_mutex_lock = (int (*)(pthread_mutex_t *))dlsym(RTLD_NEXT, "pthread_mutex_lock");
    }
    if (!real_pthread_mutex_trylock) {
        real_pthread_mutex_trylock = (int (*)(pthread_mutex_t *))dlsym(RTLD_NEXT, "pthread_mutex_trylock");
    }
    dl_iterate_phdr(patch_got_callback, NULL);
}

__attribute__((constructor))
static void on_stub_loaded(void) {
    install_compat_hooks();
}

#ifdef __cplusplus
extern "C" {
#endif

__attribute__((visibility("default")))
void godot_gdnative_init(godot_gdnative_init_options *options) {
    install_compat_hooks();
    if (!options) return;
    api = options->api_struct;
    if (!api) return;
    for (unsigned int i = 0; i < api->num_extensions; i++) {
        if (api->extensions[i] && api->extensions[i]->type == GDNATIVE_EXT_NATIVESCRIPT) {
            nativescript_api = (const godot_gdnative_ext_nativescript_api_struct *)api->extensions[i];
            break;
        }
    }
}

__attribute__((visibility("default")))
void godot_gdnative_terminate(godot_gdnative_init_options *options) {
    api = NULL;
    nativescript_api = NULL;
}

static void *dummy_create(godot_object *p_instance, void *method_data) {
    return (void *)1;
}

static void dummy_destroy(godot_object *p_instance, void *method_data, void *user_data) {
}

static godot_variant dummy_return_vector2(godot_object *p_instance, void *method_data, void *user_data, int num_args, godot_variant **args) {
    godot_variant ret;
    if (api) {
        godot_vector2 v;
        api->godot_vector2_new(&v, 0.0f, 0.0f);
        api->godot_variant_new_vector2(&ret, &v);
    }
    return ret;
}

static godot_variant dummy_return_true(godot_object *p_instance, void *method_data, void *user_data, int num_args, godot_variant **args) {
    godot_variant ret;
    if (api) api->godot_variant_new_bool(&ret, 1);
    return ret;
}

static godot_variant dummy_return_false(godot_object *p_instance, void *method_data, void *user_data, int num_args, godot_variant **args) {
    godot_variant ret;
    if (api) api->godot_variant_new_bool(&ret, 0);
    return ret;
}

static godot_variant dummy_return_real_1(godot_object *p_instance, void *method_data, void *user_data, int num_args, godot_variant **args) {
    godot_variant ret;
    if (api) api->godot_variant_new_real(&ret, 1.0);
    return ret;
}

static godot_variant dummy_return_real_0(godot_object *p_instance, void *method_data, void *user_data, int num_args, godot_variant **args) {
    godot_variant ret;
    if (api) api->godot_variant_new_real(&ret, 0.0);
    return ret;
}

static godot_variant dummy_return_int_1(godot_object *p_instance, void *method_data, void *user_data, int num_args, godot_variant **args) {
    godot_variant ret;
    if (api) api->godot_variant_new_int(&ret, 1);
    return ret;
}

static godot_variant dummy_return_nil(godot_object *p_instance, void *method_data, void *user_data, int num_args, godot_variant **args) {
    godot_variant ret;
    if (api) api->godot_variant_new_nil(&ret);
    return ret;
}

static void reg_cls(void *p_desc, const char *class_name, const char *base_name) {
    godot_instance_create_func cf = { dummy_create, NULL, NULL };
    godot_instance_destroy_func df = { dummy_destroy, NULL, NULL };
    nativescript_api->godot_nativescript_register_class(p_desc, class_name, base_name, cf, df);
}

static void reg_meth(void *p_desc, const char *class_name, const char *method_name,
                     godot_variant (*method_func)(godot_object *, void *, void *, int, godot_variant **)) {
    godot_instance_method m = { method_func, NULL, NULL };
    godot_method_attributes attr = { GODOT_METHOD_RPC_MODE_DISABLED };
    nativescript_api->godot_nativescript_register_method(p_desc, class_name, method_name, attr, m);
}

__attribute__((visibility("default")))
void godot_nativescript_init(void *desc) {
    if (!nativescript_api || !desc) return;

    // 1. MovementManagerR
    reg_cls(desc, "MovementManagerR", "Node2D");
    reg_meth(desc, "MovementManagerR", "init", dummy_return_nil);
    reg_meth(desc, "MovementManagerR", "reset", dummy_return_nil);
    reg_meth(desc, "MovementManagerR", "getVelocity", dummy_return_vector2);
    reg_meth(desc, "MovementManagerR", "getRelativeVelocity", dummy_return_vector2);
    reg_meth(desc, "MovementManagerR", "setVelocity", dummy_return_nil);
    reg_meth(desc, "MovementManagerR", "setVelocity2D", dummy_return_nil);
    reg_meth(desc, "MovementManagerR", "setVelocityRaw", dummy_return_nil);
    reg_meth(desc, "MovementManagerR", "setVelocityRawX", dummy_return_nil);
    reg_meth(desc, "MovementManagerR", "setVelocityRawY", dummy_return_nil);
    reg_meth(desc, "MovementManagerR", "forceMinimumVelocity", dummy_return_nil);
    reg_meth(desc, "MovementManagerR", "forceMaximumVelocity", dummy_return_nil);
    reg_meth(desc, "MovementManagerR", "pushAway", dummy_return_nil);
    reg_meth(desc, "MovementManagerR", "pullTowards", dummy_return_nil);
    reg_meth(desc, "MovementManagerR", "beginJump", dummy_return_nil);
    reg_meth(desc, "MovementManagerR", "forceJump", dummy_return_nil);
    reg_meth(desc, "MovementManagerR", "releaseJump", dummy_return_nil);
    reg_meth(desc, "MovementManagerR", "resetFallProgress", dummy_return_nil);
    reg_meth(desc, "MovementManagerR", "passthrough", dummy_return_nil);
    reg_meth(desc, "MovementManagerR", "isFloored", dummy_return_true);
    reg_meth(desc, "MovementManagerR", "isJumping", dummy_return_false);
    reg_meth(desc, "MovementManagerR", "isFalling", dummy_return_false);
    reg_meth(desc, "MovementManagerR", "isWalled", dummy_return_false);
    reg_meth(desc, "MovementManagerR", "isRunning", dummy_return_false);
    reg_meth(desc, "MovementManagerR", "getRunSign", dummy_return_int_1);
    reg_meth(desc, "MovementManagerR", "getRunSpeed", dummy_return_real_1);
    reg_meth(desc, "MovementManagerR", "getJumpHeight", dummy_return_real_1);
    reg_meth(desc, "MovementManagerR", "getFacing", dummy_return_int_1);
    reg_meth(desc, "MovementManagerR", "getActiveSpeed", dummy_return_real_1);
    reg_meth(desc, "MovementManagerR", "setActiveSpeed", dummy_return_nil);
    reg_meth(desc, "MovementManagerR", "resetActiveSpeed", dummy_return_nil);
    reg_meth(desc, "MovementManagerR", "setActiveJumpDuration", dummy_return_nil);
    reg_meth(desc, "MovementManagerR", "resetActiveJumpDuration", dummy_return_nil);
    reg_meth(desc, "MovementManagerR", "setJumpDurationModifier", dummy_return_nil);
    reg_meth(desc, "MovementManagerR", "resetJumpDurationModifier", dummy_return_nil);
    reg_meth(desc, "MovementManagerR", "setActiveJumpVelocity", dummy_return_nil);
    reg_meth(desc, "MovementManagerR", "resetActiveJumpVelocity", dummy_return_nil);
    reg_meth(desc, "MovementManagerR", "setSpeedModifier", dummy_return_nil);
    reg_meth(desc, "MovementManagerR", "getSpeedModifier", dummy_return_real_1);
    reg_meth(desc, "MovementManagerR", "resetSpeedModifier", dummy_return_nil);
    reg_meth(desc, "MovementManagerR", "setAddedSpeed", dummy_return_nil);
    reg_meth(desc, "MovementManagerR", "resetAddedSpeed", dummy_return_nil);
    reg_meth(desc, "MovementManagerR", "getAddedSpeed", dummy_return_real_0);
    reg_meth(desc, "MovementManagerR", "setGravity", dummy_return_nil);
    reg_meth(desc, "MovementManagerR", "getGravity", dummy_return_real_1);
    reg_meth(desc, "MovementManagerR", "getActiveGravity", dummy_return_real_1);
    reg_meth(desc, "MovementManagerR", "resetGravity", dummy_return_nil);
    reg_meth(desc, "MovementManagerR", "setGravityModifier", dummy_return_nil);
    reg_meth(desc, "MovementManagerR", "getGravityModifier", dummy_return_real_1);
    reg_meth(desc, "MovementManagerR", "resetGravityModifier", dummy_return_nil);
    reg_meth(desc, "MovementManagerR", "setGravityModifierImmediately", dummy_return_nil);
    reg_meth(desc, "MovementManagerR", "setGravityModifierSmooth", dummy_return_nil);
    reg_meth(desc, "MovementManagerR", "setFriction", dummy_return_nil);
    reg_meth(desc, "MovementManagerR", "resetFriction", dummy_return_nil);
    reg_meth(desc, "MovementManagerR", "setGroundFriction", dummy_return_nil);
    reg_meth(desc, "MovementManagerR", "toggleSnapping", dummy_return_nil);
    reg_meth(desc, "MovementManagerR", "collideWhenNormal", dummy_return_nil);
    reg_meth(desc, "MovementManagerR", "physics_move", dummy_return_vector2);
    reg_meth(desc, "MovementManagerR", "physics_collide", dummy_return_nil);

    // 2. VisualSyncR
    reg_cls(desc, "VisualSyncR", "Node2D");
    reg_meth(desc, "VisualSyncR", "init", dummy_return_nil);
    reg_meth(desc, "VisualSyncR", "_ready", dummy_return_nil);
    reg_meth(desc, "VisualSyncR", "_process", dummy_return_nil);
    reg_meth(desc, "VisualSyncR", "_physics_process", dummy_return_nil);
    reg_meth(desc, "VisualSyncR", "_notification", dummy_return_nil);
    reg_meth(desc, "VisualSyncR", "set_enabled", dummy_return_nil);
    reg_meth(desc, "VisualSyncR", "set_mode", dummy_return_nil);
    reg_meth(desc, "VisualSyncR", "set_target", dummy_return_nil);
    reg_meth(desc, "VisualSyncR", "get_target", dummy_return_nil);
    reg_meth(desc, "VisualSyncR", "teleport", dummy_return_nil);

    // 3. HelloWorld
    reg_cls(desc, "HelloWorld", "Node2D");
    reg_meth(desc, "HelloWorld", "_ready", dummy_return_nil);
    reg_meth(desc, "HelloWorld", "_process", dummy_return_nil);

    // 4. LazyTileOracle
    reg_cls(desc, "LazyTileOracle", "Node2D");
    reg_meth(desc, "LazyTileOracle", "_ready", dummy_return_nil);
    reg_meth(desc, "LazyTileOracle", "_process", dummy_return_nil);
    reg_meth(desc, "LazyTileOracle", "clear_caches", dummy_return_nil);
    reg_meth(desc, "LazyTileOracle", "garbage_collect", dummy_return_nil);
    reg_meth(desc, "LazyTileOracle", "hard_reset", dummy_return_nil);
    reg_meth(desc, "LazyTileOracle", "lazy_load", dummy_return_nil);
    reg_meth(desc, "LazyTileOracle", "refresh_areas", dummy_return_nil);
    reg_meth(desc, "LazyTileOracle", "register", dummy_return_nil);
    reg_meth(desc, "LazyTileOracle", "toggle", dummy_return_nil);
    reg_meth(desc, "LazyTileOracle", "unregister", dummy_return_nil);
    reg_meth(desc, "LazyTileOracle", "update_setup", dummy_return_nil);

    // 5. LiteOracleR
    reg_cls(desc, "LiteOracleR", "Node2D");
    reg_meth(desc, "LiteOracleR", "_ready", dummy_return_nil);
    reg_meth(desc, "LiteOracleR", "_process", dummy_return_nil);
    reg_meth(desc, "LiteOracleR", "hardReset", dummy_return_nil);
    reg_meth(desc, "LiteOracleR", "isLayerActive", dummy_return_true);
    reg_meth(desc, "LiteOracleR", "isRegistered", dummy_return_true);
    reg_meth(desc, "LiteOracleR", "register", dummy_return_nil);
    reg_meth(desc, "LiteOracleR", "registerLayer", dummy_return_nil);
    reg_meth(desc, "LiteOracleR", "register_static", dummy_return_nil);
    reg_meth(desc, "LiteOracleR", "setLayerScales", dummy_return_nil);
    reg_meth(desc, "LiteOracleR", "toggleLayer", dummy_return_nil);
    reg_meth(desc, "LiteOracleR", "toggle_raycast", dummy_return_nil);
    reg_meth(desc, "LiteOracleR", "unregister", dummy_return_nil);
    reg_meth(desc, "LiteOracleR", "unregister_static", dummy_return_nil);

    // 6. LightOracleR
    reg_cls(desc, "LightOracleR", "Node2D");
    reg_meth(desc, "LightOracleR", "_ready", dummy_return_nil);
    reg_meth(desc, "LightOracleR", "_process", dummy_return_nil);
    reg_meth(desc, "LightOracleR", "hardReset", dummy_return_nil);
    reg_meth(desc, "LightOracleR", "isRegistered", dummy_return_true);
    reg_meth(desc, "LightOracleR", "register", dummy_return_nil);
    reg_meth(desc, "LightOracleR", "toggle_raycast", dummy_return_nil);
    reg_meth(desc, "LightOracleR", "unregister", dummy_return_nil);
}

#ifdef __cplusplus
}
#endif
