#ifndef TEAMCODE_RELEASE_CRYPTO_H
#define TEAMCODE_RELEASE_CRYPTO_H
#include <stddef.h>
#ifdef __APPLE__
#include <CoreFoundation/CoreFoundation.h>
#include <Security/Security.h>
static inline int teamcode_release_verify(const unsigned char *spki, size_t spki_size,
        const unsigned char *pkcs1, size_t pkcs1_size, const unsigned char *digest, size_t digest_size,
        const unsigned char *signature, size_t signature_size) {
    if (!pkcs1 || pkcs1_size == 0 || pkcs1_size > 2048 || digest_size != 32 || signature_size != 384) return 0;
    CFDataRef data = CFDataCreate(NULL, pkcs1, (CFIndex)pkcs1_size);
    if (!data) return 0;
    const void *keys[] = {kSecAttrKeyType, kSecAttrKeyClass};
    const void *values[] = {kSecAttrKeyTypeRSA, kSecAttrKeyClassPublic};
    CFDictionaryRef attributes = CFDictionaryCreate(NULL, keys, values, 2, &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
    CFErrorRef error = NULL;
    SecKeyRef key = attributes ? SecKeyCreateWithData(data, attributes, &error) : NULL;
    if (error) { CFRelease(error); error = NULL; }
    CFDataRef hash = CFDataCreate(NULL, digest, 32);
    CFDataRef signed_data = CFDataCreate(NULL, signature, 384);
    int valid = key && hash && signed_data && SecKeyGetBlockSize(key) == 384 &&
        SecKeyVerifySignature(key, kSecKeyAlgorithmRSASignatureDigestPSSSHA256, hash, signed_data, &error);
    if (error) CFRelease(error);
    if (signed_data) CFRelease(signed_data);
    if (hash) CFRelease(hash);
    if (key) CFRelease(key);
    if (attributes) CFRelease(attributes);
    CFRelease(data);
    return valid ? 1 : 0;
}
#else
#include <dlfcn.h>
/* Resolve the platform crypto library at runtime so cross-linking needs no host OpenSSL headers/libraries. */
static inline int teamcode_release_verify(const unsigned char *spki, size_t spki_size,
        const unsigned char *pkcs1, size_t pkcs1_size, const unsigned char *digest, size_t digest_size,
        const unsigned char *signature, size_t signature_size) {
    if (!spki || spki_size == 0 || spki_size > 2048 || digest_size != 32 || signature_size != 384) return 0;
    int flags = RTLD_NOW | RTLD_LOCAL;
#ifdef RTLD_NODELETE
    flags |= RTLD_NODELETE;
#endif
    void *library = dlopen("libcrypto.so.3", flags);
    if (!library) library = dlopen("libcrypto.so.4", flags);
    if (!library) return 0;
    typedef void *(*decode_type)(void **, const unsigned char **, long);
    typedef void *(*context_type)(void *, void *);
    typedef int (*init_type)(void *);
    typedef int (*integer_type)(void *, int);
    typedef int (*digest_type)(void *, const void *);
    typedef const void *(*sha_type)(void);
    typedef int (*verify_type)(void *, const unsigned char *, size_t, const unsigned char *, size_t);
    typedef void (*free_type)(void *);
    decode_type decode = (decode_type)dlsym(library, "d2i_PUBKEY");
    context_type context_new = (context_type)dlsym(library, "EVP_PKEY_CTX_new");
    init_type initialize = (init_type)dlsym(library, "EVP_PKEY_verify_init");
    init_type key_bits = (init_type)dlsym(library, "EVP_PKEY_get_bits");
    integer_type padding = (integer_type)dlsym(library, "EVP_PKEY_CTX_set_rsa_padding");
    integer_type salt_length = (integer_type)dlsym(library, "EVP_PKEY_CTX_set_rsa_pss_saltlen");
    digest_type message_digest = (digest_type)dlsym(library, "EVP_PKEY_CTX_set_signature_md");
    digest_type mgf_digest = (digest_type)dlsym(library, "EVP_PKEY_CTX_set_rsa_mgf1_md");
    sha_type sha256 = (sha_type)dlsym(library, "EVP_sha256");
    verify_type verify = (verify_type)dlsym(library, "EVP_PKEY_verify");
    free_type context_free = (free_type)dlsym(library, "EVP_PKEY_CTX_free");
    free_type key_free = (free_type)dlsym(library, "EVP_PKEY_free");
    if (!decode || !context_new || !initialize || !key_bits || !padding || !salt_length || !message_digest || !mgf_digest || !sha256 || !verify || !context_free || !key_free) {
        dlclose(library); return 0;
    }
    const unsigned char *cursor = spki;
    void *key = decode(NULL, &cursor, (long)spki_size);
    void *context = key ? context_new(key, NULL) : NULL;
    const void *algorithm = sha256();
    int valid = key && context && algorithm && cursor == spki + spki_size && key_bits(key) == 3072 &&
        initialize(context) > 0 && padding(context, 6) > 0 && message_digest(context, algorithm) > 0 &&
        mgf_digest(context, algorithm) > 0 && salt_length(context, 32) > 0 &&
        verify(context, signature, signature_size, digest, digest_size) == 1;
    if (context) context_free(context);
    if (key) key_free(key);
    dlclose(library);
    return valid ? 1 : 0;
}
#endif
#endif
