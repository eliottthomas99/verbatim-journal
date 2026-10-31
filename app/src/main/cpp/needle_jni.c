/*
 * needle_jni.c — minimal JNI bridge for Cactus Needle (Whistle).
 *
 * Kotlin passes a verified private model path and normalized PCM samples.
 * Results cross JNI as UTF-8 bytes, never byte-by-byte UTF-16 characters.
 *
 * All Needle functions are process-global and NOT thread-safe.
 * Public Kotlin operations serialize all access, including error retrieval.
 */

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>

#include "needle.h"

/* The engine may retain pointers into the model: keep its mapping for process lifetime. */
static void *model_mapping = NULL;
static void fail(JNIEnv *env, const char *message) {
    jclass exception = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (exception) (*env)->ThrowNew(env, exception, message ? message : "Whistle native error");
}

/* ---------- JNI: needle_load ---------- */
JNIEXPORT jint JNICALL
Java_app_verbatim_asr_WhistleNative_loadModel(
    JNIEnv *env, jobject thisObj, jstring path)
{
    (void)thisObj;
    if (model_mapping) return 0;
    const char *name = (*env)->GetStringUTFChars(env, path, NULL);
    if (!name) return -1;
    int fd = open(name, O_RDONLY);
    (*env)->ReleaseStringUTFChars(env, path, name);
    struct stat info;
    if (fd < 0) { fail(env, "Cannot open Whistle model"); return -1; }
    if (fstat(fd, &info) != 0 || info.st_size != 16919407) {
        close(fd); fail(env, "Invalid Whistle model size"); return -1;
    }
    void *data = mmap(NULL, info.st_size, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (data == MAP_FAILED) { fail(env, "Cannot map Whistle model"); return -1; }
    int ret = needle_load(data, (unsigned long long)info.st_size);
    if (ret < 0) {
        fail(env, needle_last_error());
        munmap(data, info.st_size);
        return ret;
    }
    model_mapping = data;
    return ret;
}

/* ---------- JNI: needle_transcribe ---------- */
JNIEXPORT jbyteArray JNICALL
Java_app_verbatim_asr_WhistleNative_transcribePcm(
    JNIEnv *env, jobject thisObj, jfloatArray pcm, jint outCapacity)
{
    (void)thisObj;
    if (!model_mapping) { fail(env, "Whistle model not loaded"); return NULL; }
    if (outCapacity < 1024 || outCapacity > 64 * 1024 * 1024) {
        fail(env, "Invalid transcript output capacity"); return NULL;
    }
    char *transBuf = calloc((size_t)outCapacity, 1);
    if (!transBuf) { fail(env, "Cannot allocate transcript output"); return NULL; }
    jsize samples = (*env)->GetArrayLength(env, pcm);
    jfloat *pcmBuf = (*env)->GetFloatArrayElements(env, pcm, NULL);
    if (!pcmBuf) { free(transBuf); return NULL; }
    int ret = needle_transcribe(pcmBuf, samples, NULL, NULL, 1, transBuf, outCapacity);
    (*env)->ReleaseFloatArrayElements(env, pcm, pcmBuf, JNI_ABORT);
    if (ret < 0) {
        fail(env, needle_last_error()); free(transBuf); return NULL;
    }
    size_t len = strnlen(transBuf, (size_t)outCapacity);
    if (len >= (size_t)outCapacity - 1) {
        fail(env, "Transcript output capacity exceeded"); free(transBuf); return NULL;
    }
    jbyteArray result = (*env)->NewByteArray(env, (jsize)len);
    if (result) (*env)->SetByteArrayRegion(env, result, 0, (jsize)len, (jbyte *)transBuf);
    free(transBuf);
    return result;
}

/* ---------- JNI: needle_models ---------- */
JNIEXPORT jint JNICALL
Java_app_verbatim_asr_WhistleNative_modelsAvailable(
    JNIEnv *env, jobject thisObj)
{
    (void)env; (void)thisObj;
    return needle_models();
}

/* JNI OnLoad: nothing to do — native functions exported directly. */
JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM *vm, void *reserved)
{
    (void)vm; (void)reserved;
    return JNI_VERSION_1_6;
}
