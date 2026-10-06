#include <mbedtls/ctr_drbg.h>
#include <mbedtls/entropy.h>
#include <mbedtls/pk.h>
#include <mbedtls/x509_crt.h>
#include <stdio.h>
#include <string.h>

int main(int argc, char **argv)
{
    mbedtls_pk_context key;
    mbedtls_entropy_context entropy;
    mbedtls_ctr_drbg_context random;
    mbedtls_x509write_cert cert;
    unsigned char pem[8192], serial[] = {1};
    FILE *file;
    int result = 1;
    if (argc != 3) return 2;
    mbedtls_pk_init(&key);
    mbedtls_entropy_init(&entropy);
    mbedtls_ctr_drbg_init(&random);
    mbedtls_x509write_crt_init(&cert);
    if (mbedtls_ctr_drbg_seed(&random, mbedtls_entropy_func, &entropy, NULL, 0) != 0) goto done;
    if (mbedtls_pk_setup(&key, mbedtls_pk_info_from_type(MBEDTLS_PK_ECKEY)) != 0) goto done;
    if (mbedtls_ecp_gen_key(MBEDTLS_ECP_DP_SECP256R1, mbedtls_pk_ec(key), mbedtls_ctr_drbg_random, &random) != 0) goto done;
    if (mbedtls_pk_write_key_pem(&key, pem, sizeof(pem)) != 0) goto done;
    file = fopen(argv[1], "w");
    if (!file) goto done;
    fputs((char *)pem, file);
    fclose(file);
    mbedtls_x509write_crt_set_version(&cert, MBEDTLS_X509_CRT_VERSION_3);
    mbedtls_x509write_crt_set_md_alg(&cert, MBEDTLS_MD_SHA256);
    mbedtls_x509write_crt_set_subject_key(&cert, &key);
    mbedtls_x509write_crt_set_issuer_key(&cert, &key);
    if (mbedtls_x509write_crt_set_subject_name(&cert, "CN=localhost") != 0 ||
        mbedtls_x509write_crt_set_issuer_name(&cert, "CN=localhost") != 0 ||
        mbedtls_x509write_crt_set_serial_raw(&cert, serial, sizeof(serial)) != 0 ||
        mbedtls_x509write_crt_set_validity(&cert, "20200101000000", "20491231235959") != 0 ||
        mbedtls_x509write_crt_set_basic_constraints(&cert, 1, -1) != 0 ||
        mbedtls_x509write_crt_pem(&cert, pem, sizeof(pem), mbedtls_ctr_drbg_random, &random) != 0) goto done;
    file = fopen(argv[2], "w");
    if (!file) goto done;
    fputs((char *)pem, file);
    result = fclose(file) != 0;
done:
    mbedtls_x509write_crt_free(&cert);
    mbedtls_pk_free(&key);
    mbedtls_ctr_drbg_free(&random);
    mbedtls_entropy_free(&entropy);
    return result;
}
