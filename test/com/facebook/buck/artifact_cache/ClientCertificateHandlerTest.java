/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.artifact_cache;

import com.facebook.buck.artifact_cache.config.ArtifactCacheBuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.base.Charsets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import javax.net.ssl.X509KeyManager;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ClientCertificateHandlerTest {
  private static final String SAMPLE_CLIENT_CERT =
      "-----BEGIN CERTIFICATE-----\n"
          + "MIIDPzCCAicCAQIwDQYJKoZIhvcNAQEFBQAwZjELMAkGA1UEBhMCVVMxCzAJBgNV\n"
          + "BAgMAldBMRAwDgYDVQQHDAdTZWF0dGxlMRcwFQYDVQQKDA5GYWNlYm9vaywgSW5j\n"
          + "LjENMAsGA1UECwwEQnVjazEQMA4GA1UEAwwHVGVzdCBDQTAeFw0xOTA1MzAyMTA5\n"
          + "MzBaFw0yOTA1MjcyMTA5MzBaMGUxCzAJBgNVBAYTAlVTMQswCQYDVQQIDAJXQTEQ\n"
          + "MA4GA1UEBwwHU2VhdHRsZTEXMBUGA1UECgwORmFjZWJvb2ssIEluYy4xDTALBgNV\n"
          + "BAsMBEJ1Y2sxDzANBgNVBAMMBkNsaWVudDCCASIwDQYJKoZIhvcNAQEBBQADggEP\n"
          + "ADCCAQoCggEBAKxHQe2R7+R4Drf/PwXXzKQl44vD6MZh7K8JgFSlQf3xPjeeRmPi\n"
          + "NI5WqnTiBXg6qGL0X6iBIDdXXymgJvppzJuXG3qGDlBM16dmlTXPjW9tXoCl12eL\n"
          + "iVGmlHTHz8OVmVdynpFLyoFeMsaAfB1LoHED32LIFkc4nhGpRTX4WfN2YK//uLpa\n"
          + "1CBO13/GHkctpPR7gVq/Z2WZup/4zDXBw/a41U0sFiIuj2r2nsXCr1sQUHEcNTSg\n"
          + "IUvTSuKX749qeQ9eA7waGyvcoUCzRh9rAvOcRewxMwttqM7kXQQe74HeGL8fh4pP\n"
          + "j17eRxOmKhXbklmtUE/DiPXCb+Qn2aDr62kCAwEAATANBgkqhkiG9w0BAQUFAAOC\n"
          + "AQEAjSRzjE6iFt1bow5tKq0UU+s6co543B6KIBtQg9a4XQokSOZCT3JzH9paZ+ah\n"
          + "7fm/zM/eryPfLLJ0kqzRdnztOZrMj6BnEw/fRV1g20UMfRc6pI0dfXwKlZh8M/Bi\n"
          + "W2q0w02JfSkxMwaVHrMgcET+y0C/yDU5ibLNp+kpFvNLNIdIR/io62gpxzuNjxet\n"
          + "SWRFHNIhkVrB/jkqPnazb8iyHpOSvIMpK1FRbiFpRFiSSeaUsAzHzPoFBjxWe80x\n"
          + "Dvwzy7Sk9viscdMZxaAPcsKxI+5/pZG7NI+7XKiiCfMyoqVFWHesf/4qJ5UydO8Q\n"
          + "L9LdcqcDD0ZZNd/QUK9Qa2bzpw==\n"
          + "-----END CERTIFICATE-----";

  private static final String SAMPLE_CLIENT_KEY =
      "-----BEGIN PRIVATE KEY-----\n"
          + "MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQCsR0Htke/keA63\n"
          + "/z8F18ykJeOLw+jGYeyvCYBUpUH98T43nkZj4jSOVqp04gV4Oqhi9F+ogSA3V18p\n"
          + "oCb6acyblxt6hg5QTNenZpU1z41vbV6Apddni4lRppR0x8/DlZlXcp6RS8qBXjLG\n"
          + "gHwdS6BxA99iyBZHOJ4RqUU1+FnzdmCv/7i6WtQgTtd/xh5HLaT0e4Fav2dlmbqf\n"
          + "+Mw1wcP2uNVNLBYiLo9q9p7Fwq9bEFBxHDU0oCFL00ril++PankPXgO8Ghsr3KFA\n"
          + "s0YfawLznEXsMTMLbajO5F0EHu+B3hi/H4eKT49e3kcTpioV25JZrVBPw4j1wm/k\n"
          + "J9mg6+tpAgMBAAECggEAYMFYdpu61l7TLWVuONLdPOeGp21s41YF/rxgVanGqV9+\n"
          + "6O+8asaVa8eizihOmBqhjYnf25xBwrMQYClxGrymzE+OgD6f9Vti1sKXVHRv47/S\n"
          + "DoCVvLKyeagLwpahyh/P0jwucD3XytZjv/ZTPoTF5BMft7PWR0O2Cwc9NrV3l3Ld\n"
          + "ZjHLQAWBhSsvrRFao8e5//awv5PC9xz53RM6Mf3Fth/MIZfD8KTrWQ+/nUyg9LT3\n"
          + "IexJwKJZNFKyiC6UCjoka6RTW5oJxDkfeeicycUFrip1/mpm4zwq1C5oAMccZqPz\n"
          + "DDer4PxDdj0SMnnqHdoOkk2d6UlTsgDBE6poSzmT8QKBgQDcpHf+rHt1UzQ7bSUF\n"
          + "/29BeT4whk8kHr0hOekGMEPj80S150ojsAHohPHz7UOHQn/79EHRR65cEQ1UJTl4\n"
          + "9uRwN+XO3xrmb7XBUigE7fs0S32DmeknviNtqnw4T0QiSuXOicHoV32ZYnqvPNnZ\n"
          + "0Je6pNTDA3Ea6acJmz8O6WNQPQKBgQDH4rkCGrhx/GMuaIvwbRAQpasbBcZw3nbv\n"
          + "a5OSmaM9jf9kSyBtyWEy0sXETSeRaouWc5tQ3m2fbixDC8nAq4Ye1eR6Y1Ac/rwX\n"
          + "+gFpZDmU1jttYqh9YjQwhrIv6PZWBKyltbuZj2uyC0T68mN7Dbn+nH6T+AX4tC7o\n"
          + "iCnbp8funQKBgAh/XQ+13Ntb5PsU7QQaHlLLNJXx1kJx3J7W+B6A5Vx2FgNbcQOG\n"
          + "18v4ssjOLnebHAq8EXzZ4eEx1u2SsW/zMkEQJg2dkg+l5b4YR+pIsBAHiEH1P4GE\n"
          + "VSD4G+ifROR9NfSKYaltFS0/GbJ+CXXWDsHlbzxDeaurq/82t2r/mg+RAoGBALQf\n"
          + "Mdqcm5NT/UhHu7sHfM+TirIKLT1uqzyq80vLGRgSCo+lR27HZsh2uPJhGIPowCru\n"
          + "uIpSNfTSQh6U09TEfFLzKjXDouDOCE+O4ZDIWT8vIlQ68Fw0j5Ue/BlCVCFFixK0\n"
          + "xn3liQXjM+DzZgPwZafz+/h5K2BndlHiyd1/vyHdAoGBAJYSeQ2Ork8EdWxiXNTN\n"
          + "/OD5Jcysc7t3iiTJd/gUr8VTu2l40xCCdRXSwNZ/ku14rlLtoB9047sn7z0M0HMs\n"
          + "+EYga6BZ+trDW7+WtRA7vrVZWH99Xt4QySezuHuy0u7aH6/Zej6vaeV2+iNeG0jC\n"
          + "PJgKB+mYQdYHIBdbKvPFane1\n"
          + "-----END PRIVATE KEY-----";

  private static final String SAMPLE_CLIENT_INTERMEDIATE_CERT =
      "-----BEGIN CERTIFICATE-----\n"
          + "MIIDWTCCAkECAQEwDQYJKoZIhvcNAQEFBQAwczELMAkGA1UEBhMCVVMxCzAJBgNV\n"
          + "BAgMAldBMRAwDgYDVQQHDAdTZWF0dGxlMRcwFQYDVQQKDA5GYWNlYm9vaywgSW5j\n"
          + "LjENMAsGA1UECwwEQnVjazEdMBsGA1UEAwwUVGVzdCBDQSBJbnRlcm1lZGlhdGUw\n"
          + "HhcNMTkwNTMwMjEwOTMxWhcNMjkwNTI3MjEwOTMxWjByMQswCQYDVQQGEwJVUzEL\n"
          + "MAkGA1UECAwCV0ExEDAOBgNVBAcMB1NlYXR0bGUxFzAVBgNVBAoMDkZhY2Vib29r\n"
          + "LCBJbmMuMQ0wCwYDVQQLDARCdWNrMRwwGgYDVQQDDBNDbGllbnQgSW50ZXJtZWRp\n"
          + "YXRlMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA00Kn8QHOZmUeOk82\n"
          + "gEwwagypwXV08LpnAgcIObBBwY7Y0S8yVxQQeOSjCK+UpWwewi6wktSTnGqiKCnJ\n"
          + "Lh0frikHUWc/RRrfU6qfzoLrxX08RT74hM+r/Az/6FzL0GBxz+vUN7fkrClhQwnG\n"
          + "AdlKaGsBHNfIvG4wlnpFcc1VE2aavtkhqLRY2PJxnJ6rQcxSgXS1PGpiOMl7u8qe\n"
          + "RV9CpbLdsvDj3ee7rKxavinKSJcjRWCduDnXq14BZEIR0pEUL4YXKiEZuMFDYXQe\n"
          + "iDJ+SZqqg9Z3Fgv9Pi/ZNTUYy1Zli+KWf1CHojv0QZbgjxw+2Y6lHr7j8dvCLf1m\n"
          + "y7RWNwIDAQABMA0GCSqGSIb3DQEBBQUAA4IBAQCdpK9qQUu1weiU8HM6E05p86k9\n"
          + "9hDUqGIo23dG6Qgj+qo7jS0xZHESyBCQVnjvp6OUxS68wLGflkQsBO+zXz+y+qXe\n"
          + "nPfyca6wei+DLHpzPcUyP70IG4aiFIzSNqvHO2fTaM12q+iUVklUG69Mz7w7/XvZ\n"
          + "DEoLNq9/EbN9OOKO0a4+V+Ix+aiFKbHJWpwtCoQl0RPEIes7VsGA0EF6dN7VI6uo\n"
          + "qEppg7YGxah5+UJQ9Iwo5KH1HPaAjyWoZryDzdwtmDw25dyQpW5ttbqPoiqUuxJu\n"
          + "eTFMKrAoadtD7cS+cB8RYmyYE89Vq+LjddJjFjvRKYgI/8apY3HSX+klWfsq\n"
          + "-----END CERTIFICATE-----";

  private static final String SAMPLE_CLIENT_INTERMEDIATE_KEY =
      "-----BEGIN PRIVATE KEY-----\n"
          + "MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQDTQqfxAc5mZR46\n"
          + "TzaATDBqDKnBdXTwumcCBwg5sEHBjtjRLzJXFBB45KMIr5SlbB7CLrCS1JOcaqIo\n"
          + "KckuHR+uKQdRZz9FGt9Tqp/OguvFfTxFPviEz6v8DP/oXMvQYHHP69Q3t+SsKWFD\n"
          + "CcYB2UpoawEc18i8bjCWekVxzVUTZpq+2SGotFjY8nGcnqtBzFKBdLU8amI4yXu7\n"
          + "yp5FX0Klst2y8OPd57usrFq+KcpIlyNFYJ24OderXgFkQhHSkRQvhhcqIRm4wUNh\n"
          + "dB6IMn5JmqqD1ncWC/0+L9k1NRjLVmWL4pZ/UIeiO/RBluCPHD7ZjqUevuPx28It\n"
          + "/WbLtFY3AgMBAAECggEBAL3Kq0/kfIYXH+HomiEG0ZPkjnlDyWwfOj1jDeutwlVe\n"
          + "vMCpMwNx/h5t4V4DR3qZuMRg57bxjS8/yTBl4dwww+5V48IpDHlL3AixR+JiUehZ\n"
          + "S1U2blP6shq9nwYkn+IzUuwlhMENzz0v67YrSJ0Olj9aqmQ8I5XNNI9rh4nmmyDS\n"
          + "MfSkBp+rzxWFPHjl1TVptcG6RXzHeoeda0cDHNfLdnkviljHCYK/yuIBK2N+Bfmc\n"
          + "W3rH76/BzUzryx0wuCwmxt8kmGi6Pau3Ma3/f7HllOgBv5ZUJqqvzHoT6HQHp8Ia\n"
          + "0gKdeCcvb0W7/oKNfTt83lohKFkgb7DNRhiGeI08sAECgYEA78mdls9qb6Ir0CGA\n"
          + "nWIsF76iKoRe4Y/NJ2iM40DMhob01dFv5nGFAlq4LWefjZHyradHyFNgafM57bea\n"
          + "664q37mGgG7pWft/s67QudXfKAbDSWsW1Xd0iLvjSKOOL4edEG/7Gmp6NXzETkLz\n"
          + "nKwjQGHdrgTYRCremNBJWHM0qAECgYEA4YtGbKTrQC6jqe/yt3xHGASqL8vzUCPL\n"
          + "5rwvFSUEWSEudvIuajeA3KCNoYuh2FPK3qTgzpPZf3cC2DXprmXDfxznpWMegKLh\n"
          + "pWF0gEC21Y1Ot7JHfY2bKuoaSlA/iYsXHXBl07ETzxmNdQiFV8U0mQXz7E/7mrBl\n"
          + "iFZC6Sq0PjcCgYADUJw3G4LzLCDC7dOjWVoWsfH3+IB/SceiRdW8xoaNTYxQ8GZF\n"
          + "n0ghcjOdnRMdl+js8aUSZeStUkl4udMQcwXwtdXgLKhZMBrh1wbXqtc87GsCttJH\n"
          + "/TDFOyO3O1uZ2JwZQBMOmG48Ew97rX1EqzSJjVDNOQ/sUVNmdWquKFmQAQKBgHv/\n"
          + "9Wz/0rLLsYFZgWjtc6y5y9NRXuj9dTna1kvauSRDgOc2SNxuvXMO9i8NtKJZlxyH\n"
          + "K22Hjbltdevm4B3Ypv24p4afEwMICeTByqpEagDImrGV24Ykl12lrWST8AqvpLqz\n"
          + "s9gJ7+kZlFL2p1DVBBDpW+zdIGgwePHW8xx8NPJLAoGBAKPksJgw63JAHORvBjpm\n"
          + "DMztlAHs1sIWawALsW4m2/46bHzFxzVOMYeV1kp1AsnMKBCcSGAyAL5BzYlkMR/1\n"
          + "qU+C+5w8m5H+BCwldZVPEjD1nAQLrn1KA5Wuk8WddkgtSAwZ2jaIAb/9lUK5FwJS\n"
          + "J92yynqcyBnCMO6bEomTkbQL\n"
          + "-----END PRIVATE KEY-----";

  private static final String SAMPLE_CA_CERT =
      "-----BEGIN CERTIFICATE-----\n"
          + "MIIDcjCCAlqgAwIBAgIJAPk81/DAFH4kMA0GCSqGSIb3DQEBCwUAMGYxCzAJBgNV\n"
          + "BAYTAlVTMQswCQYDVQQIDAJXQTEQMA4GA1UEBwwHU2VhdHRsZTEXMBUGA1UECgwO\n"
          + "RmFjZWJvb2ssIEluYy4xDTALBgNVBAsMBEJ1Y2sxEDAOBgNVBAMMB1Rlc3QgQ0Ew\n"
          + "HhcNMTkwNTMwMjEwOTI5WhcNMjkwNTI3MjEwOTI5WjBmMQswCQYDVQQGEwJVUzEL\n"
          + "MAkGA1UECAwCV0ExEDAOBgNVBAcMB1NlYXR0bGUxFzAVBgNVBAoMDkZhY2Vib29r\n"
          + "LCBJbmMuMQ0wCwYDVQQLDARCdWNrMRAwDgYDVQQDDAdUZXN0IENBMIIBIjANBgkq\n"
          + "hkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAl2GoXs05KCd8Li+RD08/7taZu2aZGr5f\n"
          + "netg9ToU4Kh/xEl7FvrxH5CgMnlCMMQspRqg0uKR5EmynYBYO0OmRIvwkeI+yDiC\n"
          + "V0NtyYO8/vlvLNRl01ndw8SF7ZJHaA/bNVVLd6CCWdzfZAW0w4U2LZvxSPRLOksR\n"
          + "Wke+yFcIrtEhV+BSL2Pdvr/SYn+aEKdrw6fyrZkJ5XJdilc9V/FuXLNF9i9Tt+e2\n"
          + "7tQD3qiIch8S9SQKjkHDnWJa0BwJ8fs5963+IDHbr9diURD/m8bGFV5Z63YufDx6\n"
          + "qphqv5p1FG6SzFvZlyX1mjsm58y2xpTKRTr3kymFe7kfiFVKcIieFQIDAQABoyMw\n"
          + "ITAPBgNVHRMBAf8EBTADAQH/MA4GA1UdDwEB/wQEAwIBhjANBgkqhkiG9w0BAQsF\n"
          + "AAOCAQEAXDL3uaHg0nCVL/sfuNQeycGNvJuxAZfZ+nSZrTjBtxU4fcdzE+zA3T/c\n"
          + "VvUvwKQFHYgEoOI9N+uLUEIVbadVxTIxpKMVV3Py+MxcRpHAeUGfpICkIhWYBdNc\n"
          + "FeLsM65+0xzdl1XDGLp2z55jDY2ALU03VTOC/MLGSSkcdwRpZYWyFRVtQe+OwRk2\n"
          + "oRfSHkhyXFQNziCS3ieMlrEE14G4F9r78BvP3wbsEjmpfFMx8fjO/B9o3I/Rm2iE\n"
          + "VKCp6Mtor82AS/bWYewGrvA0nDAtN6ENesMUP5lahcfqtqHuoMhnKAKTXf1EJtdD\n"
          + "vIfkYFDxXoXOdC8qwAc5iw1R6etUEQ==\n"
          + "-----END CERTIFICATE-----";

  private static final String SAMPLE_CA_INTERMEDIATE_CERT =
      "-----BEGIN CERTIFICATE-----\n"
          + "MIIDejCCAmKgAwIBAgIBATANBgkqhkiG9w0BAQUFADBmMQswCQYDVQQGEwJVUzEL\n"
          + "MAkGA1UECAwCV0ExEDAOBgNVBAcMB1NlYXR0bGUxFzAVBgNVBAoMDkZhY2Vib29r\n"
          + "LCBJbmMuMQ0wCwYDVQQLDARCdWNrMRAwDgYDVQQDDAdUZXN0IENBMB4XDTE5MDUz\n"
          + "MDIxMDkzMFoXDTI5MDUyNzIxMDkzMFowczELMAkGA1UEBhMCVVMxCzAJBgNVBAgM\n"
          + "AldBMRAwDgYDVQQHDAdTZWF0dGxlMRcwFQYDVQQKDA5GYWNlYm9vaywgSW5jLjEN\n"
          + "MAsGA1UECwwEQnVjazEdMBsGA1UEAwwUVGVzdCBDQSBJbnRlcm1lZGlhdGUwggEi\n"
          + "MA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQC4GLZ5Z8uX17UuNkGpNmzhBcxX\n"
          + "bJpvQukUqzNDUG0YJNSgNLt1N83Y9j4tnxOb4hOVL6edKuFoBjw/SarKrP02Tq2I\n"
          + "bcwf0bfUZhj6lK3qDDEtRR3/n51StF5wTG1k/1/0nBSqzheYlkv1u2vY/vL5UVcy\n"
          + "WdE0TyQ032EoIbkUKxmZfqw18vzbI7X6uAFrvGfozbVK3N6y7Clra4xwV0g7hzWY\n"
          + "HKWh9YUkfaMmJShKa43BkjM5gjM9Fk1/Fmlh1zU0OKFqVQgjt6WHfmJ/Zpax20ko\n"
          + "1wDMep/3Az7OcSbNPMCc0t7cEN3Im4Bt+d4zkOOLTyihK+pxIeGfgcfaN8vPAgMB\n"
          + "AAGjJjAkMBIGA1UdEwEB/wQIMAYBAf8CAQAwDgYDVR0PAQH/BAQDAgGGMA0GCSqG\n"
          + "SIb3DQEBBQUAA4IBAQAP5M2776T1+avuo7qIbFRM/ZYVR3G7E+F2XA9I+XWgOqDZ\n"
          + "2rQRTL1wDqT8ozenDp/9erh5NHsZHdetVmAesxeX+1Br+qOFm0rJoPWwjjtTVbzg\n"
          + "+uMRrHdBvesOe6QgbaCcBlnhEb6pU5Jiu7na8spCmwAb92OTZEM1p/b8yI0OfpwE\n"
          + "0xcK9Zmaeku8G1pBtXouviyxo6f7fOw6TV2SzTz3QnV0hLAgpoA9wKtmqL5SS2mX\n"
          + "cwwKxryGtDtHJXV+8CdrOk3rIvOHEP8lz5quejRcD0oHrGnUd1mThYct0hlV5R9v\n"
          + "DJdD+9D54VS6uGsknVPNVUSf+GK0iQnY3jf2Yn1F\n"
          + "-----END CERTIFICATE-----";

  @Rule public TemporaryPaths temporaryPaths = new TemporaryPaths();
  @Rule public ExpectedException expected = ExpectedException.none();

  ArtifactCacheBuckConfig config_required;
  ArtifactCacheBuckConfig config_optional;
  private Path clientCertPath;
  private Path clientKeyPath;

  @Before
  public void setUp() throws IOException {
    clientCertPath = temporaryPaths.newFile("client.crt");
    clientKeyPath = temporaryPaths.newFile("client.key");
    Files.write(clientCertPath, SAMPLE_CLIENT_CERT.getBytes(Charsets.UTF_8));
    Files.write(clientKeyPath, SAMPLE_CLIENT_KEY.getBytes(Charsets.UTF_8));
    config_required =
        ArtifactCacheBuckConfigTest.createFromText(
            "[cache]",
            "http_client_tls_key = " + clientKeyPath.toString(),
            "http_client_tls_cert = " + clientCertPath.toString(),
            "http_client_tls_cert_required = yes");
    config_optional =
        ArtifactCacheBuckConfigTest.createFromText(
            "[cache]",
            "http_client_tls_key = " + clientKeyPath.toString(),
            "http_client_tls_cert = " + clientCertPath.toString(),
            "http_client_tls_cert_required = no");
  }

  @Test
  public void throwsIfCertIsEmpty() throws IOException {
    ArtifactCacheBuckConfig config =
        ArtifactCacheBuckConfigTest.createFromText(
            "[cache]", "http_client_tls_cert_required = yes");
    expected.expect(HumanReadableException.class);
    expected.expectMessage("Required option for certificate unset");

    ClientCertificateHandler.fromConfiguration(config);
  }

  @Test
  public void ignoreIfCertIsEmpty() throws IOException {
    ArtifactCacheBuckConfig config =
        ArtifactCacheBuckConfigTest.createFromText("[cache]", "http_client_tls_cert_required = no");
    Assert.assertFalse(ClientCertificateHandler.fromConfiguration(config).isPresent());
  }

  @Test
  public void throwsIfCertIsMissing() throws IOException {
    expected.expect(HumanReadableException.class);
    expected.expectMessage(String.format("Cannot read certificate file %s", clientCertPath));

    Files.delete(clientCertPath);

    ClientCertificateHandler.fromConfiguration(config_required);
  }

  @Test
  public void ignoreIfCertIsMissing() throws IOException {
    Files.delete(clientCertPath);

    Assert.assertFalse(ClientCertificateHandler.fromConfiguration(config_optional).isPresent());
  }

  @Test
  public void throwsIfX509CertIsInvalid() throws IOException {
    expected.expect(HumanReadableException.class);
    expected.expectMessage("No parsable X509 certificates found in");

    Files.write(clientCertPath, "INVALID CERT".getBytes(Charsets.UTF_8));

    ClientCertificateHandler.fromConfiguration(config_optional);
  }

  @Test
  public void throwsIfKeyIsMissing() throws IOException {
    expected.expect(HumanReadableException.class);
    expected.expectMessage(String.format("Cannot read private key file %s", clientKeyPath));

    Files.delete(clientKeyPath);

    ClientCertificateHandler.fromConfiguration(config_required);
  }

  @Test
  public void ignoreIfKeyIsMissing() throws IOException {
    Files.delete(clientKeyPath);

    Assert.assertFalse(ClientCertificateHandler.fromConfiguration(config_optional).isPresent());
  }

  @Test
  public void throwsIfKeyIsEmpty() throws IOException {
    ArtifactCacheBuckConfig config =
        ArtifactCacheBuckConfigTest.createFromText(
            "[cache]",
            "http_client_tls_cert = " + clientCertPath.toString(),
            "http_client_tls_cert_required = yes");

    expected.expect(HumanReadableException.class);
    expected.expectMessage("Required option for private key unset");

    ClientCertificateHandler.fromConfiguration(config);
  }

  @Test
  public void ignoreIfKeyIsEmpty() throws IOException {
    ArtifactCacheBuckConfig config =
        ArtifactCacheBuckConfigTest.createFromText(
            "[cache]",
            "http_client_tls_cert = " + clientCertPath.toString(),
            "http_client_tls_cert_required = no");

    Assert.assertFalse(ClientCertificateHandler.fromConfiguration(config).isPresent());
  }

  @Test
  public void throwsIfHeaderOrTrailerAreMissing() throws IOException {
    expected.expect(HumanReadableException.class);
    expected.expectMessage("Expected BEGIN PRIVATE KEY/END PRIVATE KEY ");

    Files.write(clientKeyPath, "TESTING".getBytes(Charsets.UTF_8));

    ClientCertificateHandler.fromConfiguration(config_optional);
  }

  @Test
  public void throwsIfBase64DecodingKeyFailsAndRequired() throws IOException {
    expected.expect(HumanReadableException.class);
    expected.expectMessage("Expected base64-encoded PKCS#8 key");

    Files.write(
        clientKeyPath,
        "-----BEGIN PRIVATE KEY-----\nNOT BASE64\n-----END PRIVATE KEY-----\n"
            .getBytes(Charsets.UTF_8));

    ClientCertificateHandler.fromConfiguration(config_optional);
  }

  @Test
  public void throwsIfKeyIsInvalid() throws IOException {
    expected.expect(HumanReadableException.class);
    expected.expectMessage(String.format("Client key at %s was not valid", clientKeyPath));

    Files.write(
        clientKeyPath,
        ("-----BEGIN PRIVATE KEY-----\n"
                + "MIIJKQIBAAKCAgEAtqFSEuAK1CbaBiKfybnYMSQp1tXmgi+WdrxRT5B9MnPxe9oo\n"
                + "-----END PRIVATE KEY-----\n")
            .getBytes(Charsets.UTF_8));

    ClientCertificateHandler.fromConfiguration(config_optional);
  }

  @Test
  public void handlesCombinedKeyAndCert() throws IOException {
    Files.write(
        clientKeyPath, (SAMPLE_CLIENT_CERT + "\n" + SAMPLE_CLIENT_KEY).getBytes(Charsets.UTF_8));

    String[] keyLines = SAMPLE_CLIENT_KEY.split("\n");
    byte[] expectedPrivateKey =
        Base64.getDecoder()
            .decode(String.join("", Arrays.copyOfRange(keyLines, 1, keyLines.length - 1)));
    String expectedPublic = "CN=Client, OU=Buck, O=\"Facebook, Inc.\", L=Seattle, ST=WA, C=US";

    Optional<ClientCertificateHandler> handler =
        ClientCertificateHandler.fromConfiguration(config_required);

    X509KeyManager keyManager = handler.get().getHandshakeCertificates().keyManager();
    String alias = keyManager.getClientAliases("RSA", null)[0];
    PrivateKey privateKey = keyManager.getPrivateKey(alias);
    String subjectName = keyManager.getCertificateChain(alias)[0].getSubjectDN().getName();

    Assert.assertArrayEquals(expectedPrivateKey, privateKey.getEncoded());
    Assert.assertEquals(expectedPublic, subjectName);
    Assert.assertFalse(handler.get().getHostnameVerifier().isPresent());
  }

  @Test
  public void handlesCombinedKeyAndCertAndIntermediateCA() throws IOException {
    Path identityPath = temporaryPaths.newFile("client.pem");
    Path identityPathReverse = temporaryPaths.newFile("client_reverse.pem");
    Files.write(
        identityPath,
        (SAMPLE_CLIENT_INTERMEDIATE_CERT
                + "\n"
                + SAMPLE_CA_INTERMEDIATE_CERT
                + "\n"
                + SAMPLE_CLIENT_INTERMEDIATE_KEY)
            .getBytes(Charsets.UTF_8));
    Files.write(
        identityPathReverse,
        (SAMPLE_CLIENT_INTERMEDIATE_KEY
                + "\n"
                + SAMPLE_CLIENT_INTERMEDIATE_CERT
                + "\n"
                + SAMPLE_CA_INTERMEDIATE_CERT)
            .getBytes(Charsets.UTF_8));

    String[] keyLines = SAMPLE_CLIENT_INTERMEDIATE_KEY.split("\n");
    byte[] expectedPrivateKey =
        Base64.getDecoder()
            .decode(String.join("", Arrays.copyOfRange(keyLines, 1, keyLines.length - 1)));

    Path[] testPaths = {identityPath, identityPathReverse};
    for (Path testPath : testPaths) {
      ArtifactCacheBuckConfig config =
          ArtifactCacheBuckConfigTest.createFromText(
              "[cache]",
              "http_client_tls_key = " + testPath.toString(),
              "http_client_tls_cert = " + testPath.toString(),
              "http_client_tls_cert_required = yes");

      String expectedPublic =
          "CN=Client Intermediate, OU=Buck, O=\"Facebook, Inc.\", L=Seattle, ST=WA, C=US";
      String expectedIntermediateCa =
          "CN=Test CA Intermediate, OU=Buck, O=\"Facebook, Inc.\", L=Seattle, ST=WA, C=US";

      Optional<ClientCertificateHandler> handler =
          ClientCertificateHandler.fromConfiguration(config);

      X509KeyManager keyManager = handler.get().getHandshakeCertificates().keyManager();
      String alias = keyManager.getClientAliases("RSA", null)[0];
      PrivateKey privateKey = keyManager.getPrivateKey(alias);
      String subjectName = keyManager.getCertificateChain(alias)[0].getSubjectDN().getName();
      String intermediateCaSubjectName =
          keyManager.getCertificateChain(alias)[1].getSubjectDN().getName();

      Assert.assertArrayEquals(expectedPrivateKey, privateKey.getEncoded());
      Assert.assertEquals(expectedPublic, subjectName);
      Assert.assertEquals(expectedIntermediateCa, intermediateCaSubjectName);
      Assert.assertFalse(handler.get().getHostnameVerifier().isPresent());
    }
  }

  @Test
  public void handlesEmptyConfig() throws IOException {
    Optional<ClientCertificateHandler> handler1 =
        ClientCertificateHandler.fromConfiguration(ArtifactCacheBuckConfigTest.createFromText());
    Optional<ClientCertificateHandler> handler2 =
        ClientCertificateHandler.fromConfiguration(
            ArtifactCacheBuckConfigTest.createFromText(
                "[cache]", "http_client_tls_key = " + clientKeyPath.toString()));
    Optional<ClientCertificateHandler> handler3 =
        ClientCertificateHandler.fromConfiguration(
            ArtifactCacheBuckConfigTest.createFromText(
                "[cache]", "http_client_tls_cert = " + clientCertPath.toString()));

    Assert.assertFalse(handler1.isPresent());
    Assert.assertFalse(handler2.isPresent());
    Assert.assertFalse(handler3.isPresent());
  }
}
