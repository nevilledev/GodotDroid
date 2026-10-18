#define _GNU_SOURCE
#include <pthread.h>
#include <dlfcn.h>
#include <unistd.h>
#include <sys/mman.h>
#include <link.h>
#include <elf.h>
#include <android/log.h>
#include <string.h>
#include <jni.h>
#include <errno.h>

#define TAG "GodotCompat"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static int (*real_pthread_mutex_lock)(pthread_mutex_t *) = NULL;
static int (*real_pthread_mutex_trylock)(pthread_mutex_t *) = NULL;
static int (*real_pthread_mutex_unlock)(pthread_mutex_t *) = NULL;

__attribute__((visibility("default")))
int hooked_pthread_mutex_lock(pthread_mutex_t *mutex) {
    if (!real_pthread_mutex_lock) {
        real_pthread_mutex_lock = (int (*)(pthread_mutex_t *))dlsym(RTLD_NEXT, "pthread_mutex_lock");
    }
    int res = real_pthread_mutex_lock ? real_pthread_mutex_lock(mutex) : 0;
    if (res == EAGAIN) {
        // In Android Bionic, recursive mutexes return EAGAIN when the internal counter hits 2047 (MUTEX_COUNTER_MASK).
        // Since the caller already owns the mutex exclusively, returning 0 prevents libc++ from throwing
        // std::__ndk1::system_error: recursive_mutex lock failed: Try again and aborting the engine.
        return 0;
    }
    return res;
}

__attribute__((visibility("default")))
int hooked_pthread_mutex_trylock(pthread_mutex_t *mutex) {
    if (!real_pthread_mutex_trylock) {
        real_pthread_mutex_trylock = (int (*)(pthread_mutex_t *))dlsym(RTLD_NEXT, "pthread_mutex_trylock");
    }
    int res = real_pthread_mutex_trylock ? real_pthread_mutex_trylock(mutex) : 0;
    if (res == EAGAIN) {
        return 0;
    }
    return res;
}

// Export pthread_mutex_lock and pthread_mutex_trylock for symbol interposition
__attribute__((visibility("default")))
int pthread_mutex_lock(pthread_mutex_t *mutex) {
    return hooked_pthread_mutex_lock(mutex);
}

__attribute__((visibility("default")))
int pthread_mutex_trylock(pthread_mutex_t *mutex) {
    return hooked_pthread_mutex_trylock(mutex);
}

static int patch_got_callback(struct dl_phdr_info *info, size_t size, void *data) {
    if (!info->dlpi_name || !info->dlpi_name[0]) return 0;
    
    // Only patch Godot engine, libc++, or OpenSL libraries
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

static void *watcher_thread(void *arg) {
    for (int i = 0; i < 15; i++) {
        install_compat_hooks();
        sleep(1);
    }
    return NULL;
}

__attribute__((constructor))
static void on_library_load(void) {
    LOGI("libgodot_compat loaded - initializing anti-crash mutex guards");
    install_compat_hooks();
    pthread_t th;
    pthread_create(&th, NULL, watcher_thread, NULL);
    pthread_detach(th);
}

__attribute__((visibility("default")))
JNIEXPORT void JNICALL Java_com_godotdroid_runtime_loader_DynamicGodotLoader_installCompatHooks(JNIEnv *env, jclass clazz) {
    LOGI("DynamicGodotLoader: manual trigger installCompatHooks");
    install_compat_hooks();
}
