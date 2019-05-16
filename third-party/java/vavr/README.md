# Vavr

We include [Vavr](https://github.com/vavr-io/vavr) (formerly known as Javaslang)
to leverage its persistent collections.

## Updating

First, do a HEAD request to check the SHA-1 of the binary you want to download:

```
$ curl -i --head http://jcenter.bintray.com/io/vavr/vavr/0.9.2/vavr-0.9.2.jar
HTTP/1.1 200 OK
Server: nginx
Date: Wed, 15 May 2019 23:37:03 GMT
Content-Type: application/content-stream
Content-Length: 819397
Connection: keep-alive
Last-Modified: Fri, 24 Nov 2017 07:49:49 GMT
ETag: ef378d285687bf75db8c1bd5e4d3e495dd1a57d5663b1e2374e05ec471d69d6f
X-Checksum-Sha1: 8a1ef529bdae2adcacd82fa0a8d666e76a73b2ac
X-Checksum-Sha2: ef378d285687bf75db8c1bd5e4d3e495dd1a57d5663b1e2374e05ec471d69d6f
Accept-Ranges: bytes

```

Then download it and check the SHA-1:

```
$ curl --location --silent --output vavr-0.9.2.jar http://jcenter.bintray.com/io/vavr/vavr/0.9.2/vavr-0.9.2.jar
$ shasum vavr-0.9.2.jar
8a1ef529bdae2adcacd82fa0a8d666e76a73b2ac  vavr-0.9.2.jar
```

Do the same thing for the source jar:

```
$ curl -i --head http://jcenter.bintray.com/io/vavr/vavr/0.9.2/vavr-0.9.2-sources.jar
HTTP/1.1 200 OK
Server: nginx
Date: Wed, 15 May 2019 23:39:42 GMT
Content-Type: application/content-stream
Content-Length: 382583
Connection: keep-alive
Last-Modified: Fri, 24 Nov 2017 07:49:53 GMT
ETag: bb58f6005d7334844741ead3b2e7c33c0a70ce8a074a0a91100c2b9f8a89343a
X-Checksum-Sha1: 6f541fd0ff058edbce64a5122d29a0a9136fc6b5
X-Checksum-Sha2: bb58f6005d7334844741ead3b2e7c33c0a70ce8a074a0a91100c2b9f8a89343a
Accept-Ranges: bytes

$ curl --location --silent --output vavr-0.9.2-sources.jar http://jcenter.bintray.com/io/vavr/vavr/0.9.2/vavr-0.9.2-sources.jar
$ shasum vavr-0.9.2-sources.jar
6f541fd0ff058edbce64a5122d29a0a9136fc6b5  vavr-0.9.2-sources.jar
```

Make sure to update the `BUCK` file in this directory with the new file names as
well as `.idea/libraries/vavr_0_9_2.xml`.
