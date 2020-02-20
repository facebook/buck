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
import com.facebook.buck.artifact_cache.thrift.BuckCacheMultiFetchResponse;
import com.facebook.buck.artifact_cache.thrift.BuckCacheResponse;
import com.facebook.buck.artifact_cache.thrift.BuckCacheStoreResponse;
import com.facebook.buck.artifact_cache.thrift.FetchResult;
import com.facebook.buck.artifact_cache.thrift.FetchResultType;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.TargetConfigurationSerializerForTests;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.parser.buildtargetparser.UnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.file.BorrowablePath;
import com.facebook.buck.io.file.LazyPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.slb.ThriftUtil;
import com.facebook.buck.support.bgtasks.TaskManagerCommandScope;
import com.facebook.buck.support.bgtasks.TestBackgroundTaskManager;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.HttpdForTests;
import com.google.common.base.Charsets;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ArtifactCachesIntegrationTest {

  /** Constants below generated with: bash testdata/gen_test_certs.sh 2>/dev/null */
  private static final String SAMPLE_CLIENT_CERT =
      "-----BEGIN CERTIFICATE-----\n"
          + "MIIDPzCCAicCAQIwDQYJKoZIhvcNAQEFBQAwZjELMAkGA1UEBhMCVVMxCzAJBgNV\n"
          + "BAgMAldBMRAwDgYDVQQHDAdTZWF0dGxlMRcwFQYDVQQKDA5GYWNlYm9vaywgSW5j\n"
          + "LjENMAsGA1UECwwEQnVjazEQMA4GA1UEAwwHVGVzdCBDQTAeFw0xOTA1MzAyMDU2\n"
          + "MjJaFw0yOTA1MjcyMDU2MjJaMGUxCzAJBgNVBAYTAlVTMQswCQYDVQQIDAJXQTEQ\n"
          + "MA4GA1UEBwwHU2VhdHRsZTEXMBUGA1UECgwORmFjZWJvb2ssIEluYy4xDTALBgNV\n"
          + "BAsMBEJ1Y2sxDzANBgNVBAMMBkNsaWVudDCCASIwDQYJKoZIhvcNAQEBBQADggEP\n"
          + "ADCCAQoCggEBANGGDDaFttgB4aKJypMvOyPSJftYFUcX9sNSHLHhRpTxb+2GHZmO\n"
          + "aeGLzCHEW8XKMyZ8+hCHGCbBu40mqNAZGYek4r0z2dJIV072xPCmmInTOfV7oD4R\n"
          + "nnYngY/I6Yqk4A0Mo/PjIxiFp5djj8AvPRG53WyEBFRPwvMGe1gsX4GXN/cU5jEH\n"
          + "XEqNFc6pRLhfDYwfm1N27tqbjSrV8dCnGEtEijOLH6SM2dMoFuOSTc0ABOtwwito\n"
          + "CkVvl56C8xRRNntHJq8/q98K0Z9NfK1vPRvR1cDw9976nilrEDv+j22ZaVD05I2q\n"
          + "fGwNxviNCGsiX9+CSrz7zwYRGPleo7gmrT0CAwEAATANBgkqhkiG9w0BAQUFAAOC\n"
          + "AQEAduhZUVCPR/diQpmNw6KD4aEk2/RMHkJZrNUfSPxaNUhRTVgSang+Ic0q4eRp\n"
          + "q72NLp84RS8CloQbfzr9yw2b65sFvp5tHlSoc88ynuEJrDm0qSnbb4elGPwt4snm\n"
          + "CELxmXDy8criPFGMTK/DjzHJny5RTmXQGJ7oDRiw3jPVtXPXaK5qC5LlXayofw62\n"
          + "V0v9RZE9/+ncICq/muwd0AbphjjkZBpj2Ya69ERCfsJZxMfdG/N2+fTPFQFO0s8H\n"
          + "CLXrSUvLCp3a3t1h8ZEecAGuYcHFwXdO+5TtssT0Kx21KGNdy+aKHhWmMIYyjaMn\n"
          + "yQ5SW7SWHUJmfeCu2KtbGXwIFw==\n"
          + "-----END CERTIFICATE-----";

  private static final String SAMPLE_CLIENT_KEY =
      "-----BEGIN PRIVATE KEY-----\n"
          + "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQDRhgw2hbbYAeGi\n"
          + "icqTLzsj0iX7WBVHF/bDUhyx4UaU8W/thh2Zjmnhi8whxFvFyjMmfPoQhxgmwbuN\n"
          + "JqjQGRmHpOK9M9nSSFdO9sTwppiJ0zn1e6A+EZ52J4GPyOmKpOANDKPz4yMYhaeX\n"
          + "Y4/ALz0Rud1shARUT8LzBntYLF+Blzf3FOYxB1xKjRXOqUS4Xw2MH5tTdu7am40q\n"
          + "1fHQpxhLRIozix+kjNnTKBbjkk3NAATrcMIraApFb5eegvMUUTZ7RyavP6vfCtGf\n"
          + "TXytbz0b0dXA8Pfe+p4paxA7/o9tmWlQ9OSNqnxsDcb4jQhrIl/fgkq8+88GERj5\n"
          + "XqO4Jq09AgMBAAECggEAFX8vWZi2fcsTn12LzzYVV0OEahlLdZPb2YZfM1DtsPJk\n"
          + "jXYpK0wVSPLS6tP+pnhsbxJ7hZ6Wt8NAvuasg01P9T7RlJ/xRUXuz0c6RYaSN/HY\n"
          + "DHu2oSelnnHHNT1j2Lm50xzs5WT0gNuVqk6ovQsbtOng8fVJjGzyj4SmuxEya1Us\n"
          + "5GnZRK+qJwa/KN2pbN6XzY8GSFJDozF0isNmqYi0px6lUKUHRcMeZ27D1Am9LLnB\n"
          + "3srRHuTmQL78hJL2GJtHGyJam99ScOPyppUh17zvqnRI2xlypd3n77yLd9m1Flqb\n"
          + "CQE2r4Xsvwr6XvdgttnRA//VWdcVcG2uJxt1SrJYgQKBgQD3TXhxaGqXz89zEOq8\n"
          + "O1wbvWDhuiBRCkmBI9+fV3SrnJdy/f8mAFezOCJsoZRPptxO5gcT1vwgk8tWZHyA\n"
          + "oI2amqOXPnwgMf4yNUiwQKSjuBqVXivqV76C5jrGhcf9jz4qSMeMNKKuqgSND4ZS\n"
          + "BV4+eExZy1DNrhhZ2Xqq8TP6qQKBgQDY5HFyQB/vBK55yjNnpkgPCqPGkYz8VJ6B\n"
          + "vUF/0gGbvyZzETZkI25n10jabVXRbyCQ/mGc504FMiCgurr2MyQFBK0WlupKo9Ip\n"
          + "I/k1UeHsGzo/V96SMv9GJVJHaZD+mf1h69d3FRX03BaS7RJMeI3ElthiLy09q07Y\n"
          + "OPUDWvzudQKBgQCQI7FNuGRYc6EgGf4XFCoNaQXsywVG9s383SHbx6eS1sRXG7/5\n"
          + "MD3tkYxO8KZ2/nRt8Biz8ZwmiL39brg6aFnggL1Uy/Cg+0KDlRb209aiLg4gfTDv\n"
          + "d5Dszq/3QcZc/X5oOt9D0vH3B9V7Ok85wzM5CfjGZYCFQTGkPOQIemmncQKBgEJj\n"
          + "SkZKUnv61tz4g1uKjivseczh6GGkFRBpOY9CXLOrgr1d22QzZCvsvaP+K3J7rWA0\n"
          + "PPSl6D+25D3OakPJy5Ctqb1sXDKUilOFa1ZixeBbRSz2XG4rpe92pQSuz27e+6vp\n"
          + "YH5Wue3FIDPA1QULMXmnInyLLDHgKbYNWRG53AmxAoGAIakgZYhnWBUDs+KPrSZl\n"
          + "GKvkEsWBV+dPEgK3M3n1/lPsK7cH2mRiKFhliLl6ARgM54cB0ND4HKbEnsM4l9Oi\n"
          + "AZ/9SLXbTb1khHSFBocRsBTnSuGu8aGELsvNNQtLz0SvNtuGsyPAqPOuP1JPQUEg\n"
          + "QuQZ0UimP/kmROvl02Peuxc=\n"
          + "-----END PRIVATE KEY-----";

  private static final String SAMPLE_CLIENT_INTERMEDIATE_CERT =
      "-----BEGIN CERTIFICATE-----\n"
          + "MIIDWTCCAkECAQEwDQYJKoZIhvcNAQEFBQAwczELMAkGA1UEBhMCVVMxCzAJBgNV\n"
          + "BAgMAldBMRAwDgYDVQQHDAdTZWF0dGxlMRcwFQYDVQQKDA5GYWNlYm9vaywgSW5j\n"
          + "LjENMAsGA1UECwwEQnVjazEdMBsGA1UEAwwUVGVzdCBDQSBJbnRlcm1lZGlhdGUw\n"
          + "HhcNMTkwNTMwMjA1NjIzWhcNMjkwNTI3MjA1NjIzWjByMQswCQYDVQQGEwJVUzEL\n"
          + "MAkGA1UECAwCV0ExEDAOBgNVBAcMB1NlYXR0bGUxFzAVBgNVBAoMDkZhY2Vib29r\n"
          + "LCBJbmMuMQ0wCwYDVQQLDARCdWNrMRwwGgYDVQQDDBNDbGllbnQgSW50ZXJtZWRp\n"
          + "YXRlMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA16kkIpIE/Vy5pDCO\n"
          + "EwGFY2EaTxTXn9Vb4WtrFwekGwWNKMY73p3FNC5Cfe3licm0MKGBCS0L/m5m53Uy\n"
          + "U2KKasy72821wZiCXmjpRQHu4XXyVlyTVRHkcqCQho54b+QDAFVgxB+i+AkU29Pe\n"
          + "9Yx4+wWiSXnC41aLUqZAlHnUwKhcvNOUKv/f9R1kIjFgJS5NA11Pgl47icglOO0B\n"
          + "2muBo6BJpJWxzs+0F5N2ncxSF/IlAzWtkThkMdDcbqls6jhLWB22thu7a/XFWsAM\n"
          + "CmR6VjJgYwvrZEo0ZMh42nMrFDTzljoP2ph6zhS/Ikc0FGGCBbfx6qWANnRACQPP\n"
          + "eB1O4QIDAQABMA0GCSqGSIb3DQEBBQUAA4IBAQAyAR5fOq3CSLVxUghAqCrB2Pj0\n"
          + "lxI5hanZNcjEva+MWpDfPQ4sOoBNjMf9WZUHCTJO6qK69+9eBCKX9R/d4d5Ykl/i\n"
          + "/L+etZnnAATjEK9cqPdNLz7yyuCBcpWGoGHI/EiPodXJ3DGn6EY7EFSyvxHxbodK\n"
          + "XJzf1av9Xou0qdMcGrHgGdEQX67If/Ys+0FQAqp84z7LWohSt+3urr8j8NdWgHbT\n"
          + "FBQ9VkK4DwE6FRxd2YfZPuXWZ8cerl5V4KJ8+SW8ovgZQnC+bFZmYAEk3hbbP67D\n"
          + "AngKh/TsLBkulUwBC41nOjeBlG//NrsH2rSU9R6AAtmgQmOpnkm4nCGYdCt4\n"
          + "-----END CERTIFICATE-----";

  private static final String SAMPLE_CLIENT_INTERMEDIATE_KEY =
      "-----BEGIN PRIVATE KEY-----\n"
          + "MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQDXqSQikgT9XLmk\n"
          + "MI4TAYVjYRpPFNef1Vvha2sXB6QbBY0oxjvencU0LkJ97eWJybQwoYEJLQv+bmbn\n"
          + "dTJTYopqzLvbzbXBmIJeaOlFAe7hdfJWXJNVEeRyoJCGjnhv5AMAVWDEH6L4CRTb\n"
          + "0971jHj7BaJJecLjVotSpkCUedTAqFy805Qq/9/1HWQiMWAlLk0DXU+CXjuJyCU4\n"
          + "7QHaa4GjoEmklbHOz7QXk3adzFIX8iUDNa2ROGQx0NxuqWzqOEtYHba2G7tr9cVa\n"
          + "wAwKZHpWMmBjC+tkSjRkyHjacysUNPOWOg/amHrOFL8iRzQUYYIFt/HqpYA2dEAJ\n"
          + "A894HU7hAgMBAAECggEBALKTKQvDM4SEUmgFmK9eNBB9aGaRUJbV+hXnd66DMjz5\n"
          + "1NAtjYehxpiVsZNK+SWmMqGOKiXp+iN9UZJY9gob75fN3cR9SwJ6gYEhh/8PQbnJ\n"
          + "i5g9YfYwMaVFFUeGE247NM0C5XSg6bZO9smsX9OdtNPO73m97EWI9SbD6XfIhgXc\n"
          + "Hktnajj4XJe9G66lbmqY4IxFgcK1pP6s5B/yL6g1FOZ2t5Sq/7AhLEZ4gQ2R65U9\n"
          + "mS79ucgfAjByuwTrh22Jpgsv4DAsKGyQUI82+3vSzMkb6y520aNuB1A8wmGCiIY3\n"
          + "t6PFPgg9e973YM1fPAGffqpjg+CxeRtg1u89uXasuEECgYEA8yjiG5t37khZCZcx\n"
          + "1dnhTxU3jSv9U/PBE/mspZUvfkE2Tul8G1EwKY+CRYv1yg70I74mr3fwdxYoN0O6\n"
          + "H+bIg0gS9Wy2wm7oTCpoZ2EjM2w2aJMq7mYP63/NqKPemQBTo5JnKvgNH6RRa5FB\n"
          + "V+IRI8rWKopcyONVMsqvbqwL60kCgYEA4wyEXRR0o0G9XEjru0LCxpIYp2xtAstq\n"
          + "RouSsM3/jedv/nEP5YfJAVw0pmfWLUaoTATm8o+IjadYtz8alhEnj1FRONXQ7FLT\n"
          + "bzfrsi1uUOa1AKfGrvdBxI0CT6S7f6I8h3h3mU9GDVGlxxLXXkQLWdaxT5JWwY62\n"
          + "QfD8BbYd7tkCgYBRQyJ9c3GbMpZ+/AZtn4kKst6D0WWx/s5R7KjkFX1vxj9uE92k\n"
          + "C3f7C7jPoTydMG4q28t17LFyOvdpsLqtGqV8KkQbvR8+z23Wtn15vx7SeqGcRUKd\n"
          + "tYIwg9+pMkqb+134Vl8gHxHTt0h2mG6r/iMYQRtd0Cu9/ytj9BS4+cpp8QKBgQCj\n"
          + "QTXPg8zWMofI2nn9nORWSWhGwhSkBMV20hb44DYXv8jsaDlo7jierMECwfjjd0G9\n"
          + "32x8Dq6+RAzrPgmMy+rpByxitINT2b5D4y6rYDVJIIoXXYvj9M+qV0XJJIbZIDtr\n"
          + "oThF2RVisEmGGcsX8c9DmrbFo1CUPlxYj8F3Ddr6CQKBgAdLkgs6ym1gf/tKCMZq\n"
          + "mPlD6Hyw2qAnH1o2gDP8Zfhwgps03ypw8ISqRdR7Y3vUzyLCzIjhKjTdHIsDCGna\n"
          + "9EQihK/j3CwYpReTd/SxiD6bwwE6cPkYF9Tr8ONLNzlWiYoDZGUaGunJgYvHM5X3\n"
          + "De7sjp39H2se6HtyfByAoMxp\n"
          + "-----END PRIVATE KEY-----";

  private static final String SAMPLE_SERVER_CERT =
      "-----BEGIN CERTIFICATE-----\n"
          + "MIIDSzCCAjMCAQMwDQYJKoZIhvcNAQEFBQAwZjELMAkGA1UEBhMCVVMxCzAJBgNV\n"
          + "BAgMAldBMRAwDgYDVQQHDAdTZWF0dGxlMRcwFQYDVQQKDA5GYWNlYm9vaywgSW5j\n"
          + "LjENMAsGA1UECwwEQnVjazEQMA4GA1UEAwwHVGVzdCBDQTAeFw0xOTA1MzAyMDU2\n"
          + "MjNaFw0yOTA1MjcyMDU2MjNaMHExCzAJBgNVBAYTAlVTMQswCQYDVQQIDAJXQTEQ\n"
          + "MA4GA1UEBwwHU2VhdHRsZTEXMBUGA1UECgwORmFjZWJvb2ssIEluYy4xDTALBgNV\n"
          + "BAsMBEJ1Y2sxGzAZBgNVBAMMEnNlcnZlci5leGFtcGxlLmNvbTCCASIwDQYJKoZI\n"
          + "hvcNAQEBBQADggEPADCCAQoCggEBAPwU/wvwFuab5Ds6XpYdoOWbJ6Vg1RyDrdPV\n"
          + "je411fsyqCsBtmlSG2MTR1M0SGlivSwirNZNcziZGUlVVT6P23hdd5OazX3m06O1\n"
          + "YHShxfOaWmCCFPzOWY5XxFQun6RiU67JM0kY0/xUM9mEeV4pc1agKznCa1V1vrZe\n"
          + "+11NJlGtlyftCWDVsIj1N78IR0N8f2d00ibMGvSKqvNWylSF8DVPSLYH+Tkki9S8\n"
          + "Iv3tvvdFL85S4y2iUvH4/EwitefI068Bti0jfhyqxQPyLKGyOlASy2PSZT3x/6zW\n"
          + "/lIp7ElMhnpT6xaUN3iVVbfFJTz/k9WJNRTYGndCImd46N+wBjECAwEAATANBgkq\n"
          + "hkiG9w0BAQUFAAOCAQEAlfDU060F24N2etxf50uro0UBnhC/8Nnsi3vJsBV+NKft\n"
          + "A/0TYyYVpilRqTdB2a7P0/X/VWPqILMwoBwnrDHpQqF1s0xSn+ZeOJxfvx/eA9ju\n"
          + "uhxthrPGdpXrNB9xR/3Dm0vg2aSyr+jKq475qAuVQdDBh5Lzz62pxEoEmZpxqwpx\n"
          + "Y7Ql7oC3mDqVJhLwNeYG4wB6YyZLCMZ/6U+TYHuEGjd/c/4V8zo5QrS8Camsuie0\n"
          + "Abbd3NPs6K3G+gQfVB5TfbnpjcyCmvyx5olfudnkpnvEPKYanHeW8lFeq1zkfwr7\n"
          + "tayXYX57o8gdErFLMqZdd2yTB9Jt6k2fcUIVF04Kzg==\n"
          + "-----END CERTIFICATE-----";

  private static final String SAMPLE_SERVER_KEY =
      "-----BEGIN PRIVATE KEY-----\n"
          + "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQD8FP8L8Bbmm+Q7\n"
          + "Ol6WHaDlmyelYNUcg63T1Y3uNdX7MqgrAbZpUhtjE0dTNEhpYr0sIqzWTXM4mRlJ\n"
          + "VVU+j9t4XXeTms195tOjtWB0ocXzmlpgghT8zlmOV8RULp+kYlOuyTNJGNP8VDPZ\n"
          + "hHleKXNWoCs5wmtVdb62XvtdTSZRrZcn7Qlg1bCI9Te/CEdDfH9ndNImzBr0iqrz\n"
          + "VspUhfA1T0i2B/k5JIvUvCL97b73RS/OUuMtolLx+PxMIrXnyNOvAbYtI34cqsUD\n"
          + "8iyhsjpQEstj0mU98f+s1v5SKexJTIZ6U+sWlDd4lVW3xSU8/5PViTUU2Bp3QiJn\n"
          + "eOjfsAYxAgMBAAECggEAVjyIk/jqnLGv/mMVgJ1aMbJGedkKjtqtWM6x41Afh/Po\n"
          + "e+32DUm9fMNXnys/qm3Q8YxbPGT6id42PFQo+WIVXrP6+E/5BJ02wcaG3N+K7K9d\n"
          + "Q668p0+ga4Wy8GmSy5Wnsx+5n8QSoTvdEQi0zHW5s2TP5M6SEYZaW+FadKyz8zSV\n"
          + "tbRAtwbdYoD9mAVYSOo3HfEUkmIs0UKtzDxpNvphyreipRpqVfN5LEarASZkhL6p\n"
          + "/IX1CoZ8hPYDkOhbIZNuAYjJx/W3I2yh6YANzrSBFYFHqNRyGUVMgEasw92y1Llr\n"
          + "lV0Qwl4e8hAmqCZkGJmVgrpA+KIWTrtA0G13DkhIAQKBgQD+n1vr9ui0YFVEfuqw\n"
          + "Wxqm6zCIVuN6bsqaipEuncN8SYalmExVw5jS+c0VxcTCAAQhhMoJRD8geNuasz6T\n"
          + "o4r+/Ewl0bfTXc6GAWg3x79Q9GMj6jiFxBNeVhocUMq5qbBo5ZFGrRJ/7GLbMRNL\n"
          + "/bC6Bz23iGEwpgIL9MFUH0dmkQKBgQD9ch5mrqtNQluHuTVKYPo2TAiEyGMKiYBQ\n"
          + "jBkVNmA++Q71dQC/uhTAqxHc8c6Uy18IONGXXB/uVaSo/0rDA+dr3csJy19BC0ZU\n"
          + "ltF9M3FK5NES67E5buo2CKVB+2f7YGUTUYWBwJ5uPVRQgMp2vbaNHdCthMGltXaJ\n"
          + "I09/PgS1oQKBgCIUqnUXA2bBTihw5HDegN6+tCxLlP8aPTwaN/yJWVUVclRP4kfI\n"
          + "engiv3SemAtvfR4PbAt5ewmZo6s9Oq8AQOaIVpjpTTWZZL9DCPQOZGktjOeNvisJ\n"
          + "Z55E4BHSLpBTSi2ALMXM/KDqvwCfvPl652C+/1/FfVzJm5SGGipsVV5BAoGAcqLK\n"
          + "g3FgBCsOkX5BT2o74pFTjRPCUILPKh+kPMcCk5k9neKVOyNkvZzjEIfDA+RVELf6\n"
          + "fTbrLndIajRG0ZyTcWO5sa1uYVJDNoGdMb8x8Ek9FAzNdYfoohYZAZZAeyAnt6w4\n"
          + "8e54+q381AjdCwZWas/gkouot1YzzmXNUGVx5eECgYEAtC0gT8zIlWQ6TotgvCwd\n"
          + "WgXfS7WIiDfxv51Y1q5jWHXAPitMRWZmtMeXwYP6oknc9XKkjN6zamQR139aFWdr\n"
          + "BeFPurVtSY/VtUIOqi0dW+XbX/355/csBD8WT7TLVE2tBcXD8c7ZsZQjbwW2c3Vh\n"
          + "LIo/pVFfIapEdT/EJIIYfBI=\n"
          + "-----END PRIVATE KEY-----";

  private static final String SAMPLE_CA_CERT =
      "-----BEGIN CERTIFICATE-----\n"
          + "MIIDcjCCAlqgAwIBAgIJAO4mYsTXHxMYMA0GCSqGSIb3DQEBCwUAMGYxCzAJBgNV\n"
          + "BAYTAlVTMQswCQYDVQQIDAJXQTEQMA4GA1UEBwwHU2VhdHRsZTEXMBUGA1UECgwO\n"
          + "RmFjZWJvb2ssIEluYy4xDTALBgNVBAsMBEJ1Y2sxEDAOBgNVBAMMB1Rlc3QgQ0Ew\n"
          + "HhcNMTkwNTMwMjA1NjIwWhcNMjkwNTI3MjA1NjIwWjBmMQswCQYDVQQGEwJVUzEL\n"
          + "MAkGA1UECAwCV0ExEDAOBgNVBAcMB1NlYXR0bGUxFzAVBgNVBAoMDkZhY2Vib29r\n"
          + "LCBJbmMuMQ0wCwYDVQQLDARCdWNrMRAwDgYDVQQDDAdUZXN0IENBMIIBIjANBgkq\n"
          + "hkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA7rFh9hIO3xKzJ7f75o6yuu/CzDvnEP1C\n"
          + "fcYsgJCs0U3rA7hHVehRXSxin/+2KCxHOx7b0JvYuLXzPXgXzqNf4gos4ph3WdWi\n"
          + "sBNUEAPshmunAKuII9eBt3In84pRW2iL8vj4la40TbFq0WQ64kCX49wBXxLCApV2\n"
          + "tMwZ/11UTXyZjgKXZrZmI/QNOKvj1P5O63qM7O8JXWis8jYK267OmmsJQS7PJ1/f\n"
          + "SSTCpLf6CbcB1abRz79jdoPn/uU+ydMM8ayoVKIjhLLSf8rr4bca44PrMz3m8FuY\n"
          + "Mr7RaUOqwfZivQ3sDrKHYyX2WiUWKJJjf5pYRUMOzfOX2Fc+jzxjLQIDAQABoyMw\n"
          + "ITAPBgNVHRMBAf8EBTADAQH/MA4GA1UdDwEB/wQEAwIBhjANBgkqhkiG9w0BAQsF\n"
          + "AAOCAQEAanVsRjwx4hi/iCqlHaiD169sDqWdbErg7n/LQ/JBbTh/1GXWdFOPau/t\n"
          + "n3+gEFQ7r+zn38hv5jyzeLqS0H2d9+q8mx4pXfNZf/ObG9NQ+02jEjp2t5CjmBhE\n"
          + "oXgrNy40idVOnDlBApUfeMloxJVuP7LL7Oy8E0JOgKJTnpKxJFlHPcB3WH9YoMBy\n"
          + "0nS20fxyfYPTjq94iA+CAwPrQaRHjK3teI4BoxAaIV7cIN1IYVhoB/8Hld/XdPv3\n"
          + "5LvInUUNrEpDrlOpoQNktOO/IilZAvHAu30IgwstvwwzxxW0IGJuIr0HwVc519J+\n"
          + "PcTs9yXIjm6nCxglWVVF3flETDqYeQ==\n"
          + "-----END CERTIFICATE-----";

  private static final String SAMPLE_CA_INTERMEDIATE_CERT =
      "-----BEGIN CERTIFICATE-----\n"
          + "MIIDejCCAmKgAwIBAgIBATANBgkqhkiG9w0BAQUFADBmMQswCQYDVQQGEwJVUzEL\n"
          + "MAkGA1UECAwCV0ExEDAOBgNVBAcMB1NlYXR0bGUxFzAVBgNVBAoMDkZhY2Vib29r\n"
          + "LCBJbmMuMQ0wCwYDVQQLDARCdWNrMRAwDgYDVQQDDAdUZXN0IENBMB4XDTE5MDUz\n"
          + "MDIwNTYyMFoXDTI5MDUyNzIwNTYyMFowczELMAkGA1UEBhMCVVMxCzAJBgNVBAgM\n"
          + "AldBMRAwDgYDVQQHDAdTZWF0dGxlMRcwFQYDVQQKDA5GYWNlYm9vaywgSW5jLjEN\n"
          + "MAsGA1UECwwEQnVjazEdMBsGA1UEAwwUVGVzdCBDQSBJbnRlcm1lZGlhdGUwggEi\n"
          + "MA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQCbdLIJ+u+2BTz3yIOAN9IoQW/X\n"
          + "gllNdV8nlihaBWKSE376MnMgCYfqqMw81bxceAgWqUv2WblUrUWJ+6isFmJkN63J\n"
          + "RZ3d1e3VQVwWt68zlt5BMHr0pTqzgLUv7WtYidADAGoMp1CD4WQ5utwL+DpjU21e\n"
          + "5Dr6TcTQ1XDSJNwOFOGTI0vkv/qKv/v0nersNfiFd4XLq8WGKLCQewvtY6b/St3h\n"
          + "8bDK9alJ8VmtIPUfEFBhdoYjKpddd5Zdo8hGd1+ek9eKdfvGISSV8rTYdCJfiTU9\n"
          + "mS+v0x9IuMZncAdFpyEKdN+LdNiZOqxmja1+1SYyCUSrNvH7i9WIW1KuwdnhAgMB\n"
          + "AAGjJjAkMBIGA1UdEwEB/wQIMAYBAf8CAQAwDgYDVR0PAQH/BAQDAgGGMA0GCSqG\n"
          + "SIb3DQEBBQUAA4IBAQDWZjU3t/LKOvIMY3drT9jnYQLA+OlzainxcMdYErrXA8pt\n"
          + "R6rn81L2NRKSu+vFv+NTXSFEiGksjyNaA1TKOKF6VHwVwY50UAVacgza9LQdF9FZ\n"
          + "Ya8a1WK2VErtCNx60lIcn5jspgmJVhySxTTT/LjIazpfmcEd7rJLZmIKsoRh5DvF\n"
          + "QInBNCps+BDYE2blpr4fulkeQ5sTjgiFdTrxojdKZ+C8PsLD0pAsyWQBDRNWdGDe\n"
          + "POYDfxk/o1uSNx7gGQy1xM46IpYhNTfsO8FSm6pXTCKp1tGJSyLLEOKKmANxMBaM\n"
          + "mD6GGjxb1ouFi7n4KMgAY4s6YXaw332D2WB+j6rS\n"
          + "-----END CERTIFICATE-----";

  private static final RuleKey ruleKey = new RuleKey("00000000000000000000000000000000");
  private static final Path outputPath = Paths.get("output/file");
  private static final BuildId BUILD_ID = new BuildId("test");

  @Rule public TemporaryPaths tempDir = new TemporaryPaths();

  private TestBackgroundTaskManager bgTaskManager;
  private TaskManagerCommandScope managerScope;
  private Path clientIntermediateIdentityPath;
  private Path clientCertPath;
  private Path clientKeyPath;
  private Path serverCertPath;
  private Path serverKeyPath;
  private Path caCertPath;
  private X509Certificate clientCert;
  private ClientCertificateHandler.CertificateInfo clientIntermediateCertInfo;

  @Before
  public void setUp() throws IOException {
    bgTaskManager = TestBackgroundTaskManager.of();
    managerScope = bgTaskManager.getNewScope(BUILD_ID);

    clientIntermediateIdentityPath = tempDir.newFile("client_intermediate.pem");
    clientCertPath = tempDir.newFile("client.crt");
    clientKeyPath = tempDir.newFile("client.key");
    serverCertPath = tempDir.newFile("server.crt");
    serverKeyPath = tempDir.newFile("server.key");
    caCertPath = tempDir.newFile("ca.crt");

    Files.write(
        clientIntermediateIdentityPath,
        (SAMPLE_CLIENT_INTERMEDIATE_CERT
                + "\n"
                + SAMPLE_CA_INTERMEDIATE_CERT
                + "\n"
                + SAMPLE_CLIENT_INTERMEDIATE_KEY)
            .getBytes(Charsets.UTF_8));
    Files.write(clientCertPath, SAMPLE_CLIENT_CERT.getBytes(Charsets.UTF_8));
    Files.write(clientKeyPath, SAMPLE_CLIENT_KEY.getBytes(Charsets.UTF_8));
    Files.write(serverCertPath, SAMPLE_SERVER_CERT.getBytes(Charsets.UTF_8));
    Files.write(serverKeyPath, SAMPLE_SERVER_KEY.getBytes(Charsets.UTF_8));
    Files.write(caCertPath, SAMPLE_CA_CERT.getBytes(Charsets.UTF_8));
    clientCert =
        ClientCertificateHandler.parseCertificateChain(Optional.of(clientCertPath), true)
            .get()
            .getPrimaryCert();
    clientIntermediateCertInfo =
        ClientCertificateHandler.parseCertificateChain(
                Optional.of(clientIntermediateIdentityPath), true)
            .get();
  }

  @After
  public void tearDown() throws InterruptedException {
    managerScope.close();
    bgTaskManager.shutdown(1, TimeUnit.SECONDS);
  }

  @Test
  public void testUsesClientTlsCertsForHttpsFetch() throws Exception {
    NotFoundHandler handler = new NotFoundHandler(false, false);
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuckEventBus buckEventBus = BuckEventBusForTests.newInstance();

    try (HttpdForTests server = new HttpdForTests(caCertPath, serverCertPath, serverKeyPath)) {
      server.addHandler(handler);
      server.start();

      ArtifactCacheBuckConfig cacheConfig =
          ArtifactCacheBuckConfigTest.createFromText(
              "[cache]",
              "mode = http",
              "http_url = " + server.getRootUri(),
              "http_client_tls_key = " + clientKeyPath.toString(),
              "http_client_tls_cert = " + clientCertPath.toString(),
              "http_client_tls_ca = " + caCertPath.toString());

      CacheResult result;
      try (ArtifactCache artifactCache =
          newArtifactCache(buckEventBus, projectFilesystem, cacheConfig).remoteOnlyInstance()) {

        result =
            artifactCache
                .fetchAsync(
                    BuildTargetFactory.newInstance("//:foo"),
                    ruleKey,
                    LazyPath.ofInstance(outputPath))
                .get();
      }

      Assert.assertEquals(result.cacheError().orElse(""), CacheResultType.MISS, result.getType());
      Assert.assertEquals(1, handler.peerCertificates.size());
      Assert.assertEquals(1, handler.peerCertificates.get(0).length);
      Assert.assertEquals(clientCert, handler.peerCertificates.get(0)[0]);
    }
  }

  @Test
  public void testUsesClientTlsCertsForHttpsFetchIntermediate() throws Exception {
    NotFoundHandler handler = new NotFoundHandler(false, false);
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuckEventBus buckEventBus = BuckEventBusForTests.newInstance();

    try (HttpdForTests server = new HttpdForTests(caCertPath, serverCertPath, serverKeyPath)) {
      server.addHandler(handler);
      server.start();

      ArtifactCacheBuckConfig cacheConfig =
          ArtifactCacheBuckConfigTest.createFromText(
              "[cache]",
              "mode = http",
              "http_url = " + server.getRootUri(),
              "http_client_tls_key = " + clientIntermediateIdentityPath.toString(),
              "http_client_tls_cert = " + clientIntermediateIdentityPath.toString(),
              "http_client_tls_ca = " + caCertPath.toString());

      CacheResult result;
      try (ArtifactCache artifactCache =
          newArtifactCache(buckEventBus, projectFilesystem, cacheConfig).remoteOnlyInstance()) {

        result =
            artifactCache
                .fetchAsync(
                    BuildTargetFactory.newInstance("//:foo"),
                    ruleKey,
                    LazyPath.ofInstance(outputPath))
                .get();
      }

      Assert.assertEquals(result.cacheError().orElse(""), CacheResultType.MISS, result.getType());
      Assert.assertEquals(1, handler.peerCertificates.size());
      Assert.assertEquals(2, handler.peerCertificates.get(0).length);
      Assert.assertEquals(
          clientIntermediateCertInfo.getPrimaryCert(), handler.peerCertificates.get(0)[0]);
      Assert.assertEquals(
          clientIntermediateCertInfo.getChain().get(0), handler.peerCertificates.get(0)[1]);
    }
  }

  @Test
  public void testUsesClientTlsCertsForThriftFetch() throws Exception {
    NotFoundHandler handler = new NotFoundHandler(true, false);
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuckEventBus buckEventBus = BuckEventBusForTests.newInstance();

    try (HttpdForTests server = new HttpdForTests(caCertPath, serverCertPath, serverKeyPath)) {
      server.addHandler(handler);
      server.start();

      ArtifactCacheBuckConfig cacheConfig =
          ArtifactCacheBuckConfigTest.createFromText(
              "[cache]",
              "mode = thrift_over_http",
              "http_url = " + server.getRootUri(),
              "hybrid_thrift_endpoint = /hybrid_thrift",
              "http_client_tls_key = " + clientKeyPath.toString(),
              "http_client_tls_cert = " + clientCertPath.toString(),
              "http_client_tls_ca = " + caCertPath.toString());

      CacheResult result;
      try (ArtifactCache artifactCache =
          newArtifactCache(buckEventBus, projectFilesystem, cacheConfig).remoteOnlyInstance()) {

        result =
            artifactCache
                .fetchAsync(
                    BuildTargetFactory.newInstance("//:foo"),
                    ruleKey,
                    LazyPath.ofInstance(outputPath))
                .get();
      }

      Assert.assertEquals(CacheResultType.MISS, result.getType());
      Assert.assertEquals(1, handler.peerCertificates.size());
      Assert.assertEquals(1, handler.peerCertificates.get(0).length);
      Assert.assertEquals(clientCert, handler.peerCertificates.get(0)[0]);
    }
  }

  @Test
  public void testUsesClientTlsCertsForThriftFetchIntermediate() throws Exception {
    NotFoundHandler handler = new NotFoundHandler(true, false);
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuckEventBus buckEventBus = BuckEventBusForTests.newInstance();

    try (HttpdForTests server = new HttpdForTests(caCertPath, serverCertPath, serverKeyPath)) {
      server.addHandler(handler);
      server.start();

      ArtifactCacheBuckConfig cacheConfig =
          ArtifactCacheBuckConfigTest.createFromText(
              "[cache]",
              "mode = thrift_over_http",
              "http_url = " + server.getRootUri(),
              "hybrid_thrift_endpoint = /hybrid_thrift",
              "http_client_tls_key = " + clientIntermediateIdentityPath.toString(),
              "http_client_tls_cert = " + clientIntermediateIdentityPath.toString(),
              "http_client_tls_ca = " + caCertPath.toString());

      CacheResult result;
      try (ArtifactCache artifactCache =
          newArtifactCache(buckEventBus, projectFilesystem, cacheConfig).remoteOnlyInstance()) {

        result =
            artifactCache
                .fetchAsync(
                    BuildTargetFactory.newInstance("//:foo"),
                    ruleKey,
                    LazyPath.ofInstance(outputPath))
                .get();
      }

      Assert.assertEquals(result.cacheError().orElse(""), CacheResultType.MISS, result.getType());
      Assert.assertEquals(1, handler.peerCertificates.size());
      Assert.assertEquals(2, handler.peerCertificates.get(0).length);
      Assert.assertEquals(
          clientIntermediateCertInfo.getPrimaryCert(), handler.peerCertificates.get(0)[0]);
      Assert.assertEquals(
          clientIntermediateCertInfo.getChain().get(0), handler.peerCertificates.get(0)[1]);
    }
  }

  @Test
  public void testUsesClientTlsCertsForHttpsStore() throws Exception {
    NotFoundHandler handler = new NotFoundHandler(false, true);
    String data = "data";
    BuckEventBus buckEventBus = BuckEventBusForTests.newInstance();
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    projectFilesystem.writeContentsToPath(data, outputPath);

    try (HttpdForTests server = new HttpdForTests(caCertPath, serverCertPath, serverKeyPath)) {
      server.addHandler(handler);
      server.start();

      ArtifactCacheBuckConfig cacheConfig =
          ArtifactCacheBuckConfigTest.createFromText(
              "[cache]",
              "mode = http",
              "http_url = " + server.getRootUri(),
              "http_client_tls_key = " + clientKeyPath.toString(),
              "http_client_tls_cert = " + clientCertPath.toString(),
              "http_client_tls_ca = " + caCertPath.toString());

      try (ArtifactCache artifactCache =
          newArtifactCache(buckEventBus, projectFilesystem, cacheConfig).remoteOnlyInstance()) {

        artifactCache
            .store(
                ArtifactInfo.builder().addRuleKeys(ruleKey).build(),
                BorrowablePath.borrowablePath(outputPath))
            .get();
      }

      Assert.assertEquals(1, handler.peerCertificates.size());
      Assert.assertEquals(1, handler.peerCertificates.get(0).length);
      Assert.assertEquals(clientCert, handler.peerCertificates.get(0)[0]);
    }
  }

  @Test
  public void testUsesClientTlsCertsForHttpsStoreIntermediate() throws Exception {
    NotFoundHandler handler = new NotFoundHandler(false, true);
    String data = "data";
    BuckEventBus buckEventBus = BuckEventBusForTests.newInstance();
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    projectFilesystem.writeContentsToPath(data, outputPath);

    try (HttpdForTests server = new HttpdForTests(caCertPath, serverCertPath, serverKeyPath)) {
      server.addHandler(handler);
      server.start();

      ArtifactCacheBuckConfig cacheConfig =
          ArtifactCacheBuckConfigTest.createFromText(
              "[cache]",
              "mode = http",
              "http_url = " + server.getRootUri(),
              "http_client_tls_key = " + clientIntermediateIdentityPath.toString(),
              "http_client_tls_cert = " + clientIntermediateIdentityPath.toString(),
              "http_client_tls_ca = " + caCertPath.toString());

      try (ArtifactCache artifactCache =
          newArtifactCache(buckEventBus, projectFilesystem, cacheConfig).remoteOnlyInstance()) {

        artifactCache
            .store(
                ArtifactInfo.builder().addRuleKeys(ruleKey).build(),
                BorrowablePath.borrowablePath(outputPath))
            .get();
      }

      Assert.assertEquals(1, handler.peerCertificates.size());
      Assert.assertEquals(2, handler.peerCertificates.get(0).length);
      Assert.assertEquals(
          clientIntermediateCertInfo.getPrimaryCert(), handler.peerCertificates.get(0)[0]);
      Assert.assertEquals(
          clientIntermediateCertInfo.getChain().get(0), handler.peerCertificates.get(0)[1]);
    }
  }

  @Test
  public void testUsesClientTlsCertsForThriftStore() throws Exception {
    NotFoundHandler handler = new NotFoundHandler(true, true);
    String data = "data";
    BuckEventBus buckEventBus = BuckEventBusForTests.newInstance();
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    projectFilesystem.writeContentsToPath(data, outputPath);

    try (HttpdForTests server = new HttpdForTests(caCertPath, serverCertPath, serverKeyPath)) {
      server.addHandler(handler);
      server.start();

      ArtifactCacheBuckConfig cacheConfig =
          ArtifactCacheBuckConfigTest.createFromText(
              "[cache]",
              "mode = thrift_over_http",
              "http_url = " + server.getRootUri(),
              "hybrid_thrift_endpoint = /hybrid_thrift",
              "http_client_tls_key = " + clientKeyPath.toString(),
              "http_client_tls_cert = " + clientCertPath.toString(),
              "http_client_tls_ca = " + caCertPath.toString());

      try (ArtifactCache artifactCache =
          newArtifactCache(buckEventBus, projectFilesystem, cacheConfig).remoteOnlyInstance()) {

        artifactCache
            .store(
                ArtifactInfo.builder().addRuleKeys(ruleKey).build(),
                BorrowablePath.borrowablePath(outputPath))
            .get();
      }

      Assert.assertEquals(1, handler.peerCertificates.size());
      Assert.assertEquals(1, handler.peerCertificates.get(0).length);
      Assert.assertEquals(clientCert, handler.peerCertificates.get(0)[0]);
    }
  }

  @Test
  public void testUsesClientTlsCertsForThriftStoreIntermediate() throws Exception {
    NotFoundHandler handler = new NotFoundHandler(true, true);
    String data = "data";
    BuckEventBus buckEventBus = BuckEventBusForTests.newInstance();
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    projectFilesystem.writeContentsToPath(data, outputPath);

    try (HttpdForTests server = new HttpdForTests(caCertPath, serverCertPath, serverKeyPath)) {
      server.addHandler(handler);
      server.start();

      ArtifactCacheBuckConfig cacheConfig =
          ArtifactCacheBuckConfigTest.createFromText(
              "[cache]",
              "mode = thrift_over_http",
              "http_url = " + server.getRootUri(),
              "hybrid_thrift_endpoint = /hybrid_thrift",
              "http_client_tls_key = " + clientIntermediateIdentityPath.toString(),
              "http_client_tls_cert = " + clientIntermediateIdentityPath.toString(),
              "http_client_tls_ca = " + caCertPath.toString());

      try (ArtifactCache artifactCache =
          newArtifactCache(buckEventBus, projectFilesystem, cacheConfig).remoteOnlyInstance()) {

        artifactCache
            .store(
                ArtifactInfo.builder().addRuleKeys(ruleKey).build(),
                BorrowablePath.borrowablePath(outputPath))
            .get();
      }

      Assert.assertEquals(1, handler.peerCertificates.size());
      Assert.assertEquals(2, handler.peerCertificates.get(0).length);
      Assert.assertEquals(
          clientIntermediateCertInfo.getPrimaryCert(), handler.peerCertificates.get(0)[0]);
      Assert.assertEquals(
          clientIntermediateCertInfo.getChain().get(0), handler.peerCertificates.get(0)[1]);
    }
  }

  private ArtifactCaches newArtifactCache(
      BuckEventBus buckEventBus,
      ProjectFilesystem projectFilesystem,
      ArtifactCacheBuckConfig cacheConfig) {
    CellPathResolver cellPathResolver = TestCellPathResolver.get(projectFilesystem);
    UnconfiguredBuildTargetViewFactory unconfiguredBuildTargetFactory =
        new ParsingUnconfiguredBuildTargetViewFactory();
    Optional<ClientCertificateHandler> clientCertificateHandler =
        ClientCertificateHandler.fromConfiguration(
            cacheConfig, Optional.of((url, session) -> true));
    return new ArtifactCaches(
        cacheConfig,
        buckEventBus,
        target ->
            unconfiguredBuildTargetFactory.create(target, cellPathResolver.getCellNameResolver()),
        TargetConfigurationSerializerForTests.create(cellPathResolver),
        projectFilesystem,
        Optional.empty(),
        MoreExecutors.newDirectExecutorService(),
        MoreExecutors.newDirectExecutorService(),
        MoreExecutors.newDirectExecutorService(),
        managerScope,
        "test://",
        "myhostname",
        clientCertificateHandler);
  }

  class NotFoundHandler extends AbstractHandler {

    private final boolean isThrift;
    private final boolean isStore;
    List<X509Certificate[]> peerCertificates = new ArrayList<>();

    public NotFoundHandler(boolean isThrift, boolean isStore) {
      this.isThrift = isThrift;
      this.isStore = isStore;
    }

    @Override
    public void handle(
        String s,
        Request request,
        HttpServletRequest httpServletRequest,
        HttpServletResponse httpServletResponse)
        throws IOException {

      X509Certificate[] certs =
          (X509Certificate[])
              httpServletRequest.getAttribute("javax.servlet.request.X509Certificate");
      peerCertificates.add(certs);
      if (isThrift) {
        httpServletResponse.setStatus(HttpServletResponse.SC_OK);

        BuckCacheResponse response = new BuckCacheResponse();
        response.setWasSuccessful(true);

        if (isStore) {
          BuckCacheStoreResponse storeResponse = new BuckCacheStoreResponse();
          response.setStoreResponse(storeResponse);
        } else {
          BuckCacheMultiFetchResponse multiFetchResponse = new BuckCacheMultiFetchResponse();
          List<FetchResult> fetchResults = new ArrayList<>();
          fetchResults.add(new FetchResult().setResultType(FetchResultType.MISS));
          multiFetchResponse.setResults(fetchResults);
          response.setMultiFetchResponse(multiFetchResponse);
        }

        byte[] serialized = ThriftUtil.serialize(ThriftArtifactCache.PROTOCOL, response);
        httpServletResponse.setContentType("application/octet-stream");
        httpServletResponse
            .getOutputStream()
            .write(ByteBuffer.allocate(4).putInt(serialized.length).array());
        httpServletResponse.getOutputStream().write(serialized);
        httpServletResponse.getOutputStream().close();
      } else {
        if (isStore) {
          httpServletResponse.setStatus(HttpServletResponse.SC_OK);
        } else {
          httpServletResponse.setStatus(HttpServletResponse.SC_NOT_FOUND);
        }
      }
      request.setHandled(true);
    }
  }
}
