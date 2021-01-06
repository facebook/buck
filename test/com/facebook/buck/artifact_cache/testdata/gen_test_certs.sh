#!/bin/bash
# vim: set et sw=2 ts=2:

# generate certificate hierachy for integration testing
# purposes. run as `bash gen_test_certs.sh 2>/dev/null`
# to generate just the java code snippet

LOCATION="/C=US/ST=WA/L=Seattle/O=Facebook, Inc./OU=Buck"
CA_SUBJ="$LOCATION/CN=Test CA"
CA_INTERMEDIATE_SUBJ="$LOCATION/CN=Test CA Intermediate"
CLIENT_SUBJ="$LOCATION/CN=Client"
CLIENT_INTERMEDIATE_SUBJ="$LOCATION/CN=Client Intermediate"
SERVER_SUBJ="$LOCATION/CN=server.example.com"
DAYS=3650
KEYOPTS="-newkey rsa -pkeyopt rsa_keygen_bits:2048 -nodes"


read -r -d '' OPENSSL_CNF <<'EOT'
[ req ]
distinguished_name  = req_distinguished_name
x509_extensions     = v3_ca

[ req_distinguished_name ]

[ v3_ca ]
basicConstraints = critical, CA:true
keyUsage = critical, digitalSignature, cRLSign, keyCertSign

[ v3_intermediate_ca ]
basicConstraints = critical, CA:true, pathlen:0
keyUsage = critical, digitalSignature, cRLSign, keyCertSign
EOT


temp_dir=$(mktemp -d)
# comment this if you want to access generated files manually
trap "rm -f \"$temp_dir/\"*.{crt,key,csr}; rmdir \"$temp_dir\"" exit 
cd "$temp_dir"
(
	echo "Creating actual files in $temp_dir"
	
	openssl req -new -x509 -days $DAYS $KEYOPTS -keyout ca.key -out ca.crt \
    -subj "$CA_SUBJ" -config <(cat <<< "$OPENSSL_CNF")

	openssl req -new $KEYOPTS -keyout ca_intermediate.key \
    -out ca_intermediate.csr -subj "$CA_INTERMEDIATE_SUBJ"
	openssl x509 -req -days $DAYS -in ca_intermediate.csr -CA ca.crt \
    -CAkey ca.key -set_serial 01 -out ca_intermediate.crt \
    -extfile <(cat <<< "$OPENSSL_CNF") -extensions v3_intermediate_ca

	openssl req -new $KEYOPTS -keyout client.key -out client.csr \
    -subj "$CLIENT_SUBJ"
	openssl x509 -req -days $DAYS -in client.csr -CA ca.crt -CAkey ca.key \
    -set_serial 02 -out client.crt

	openssl req -new $KEYOPTS -keyout client_intermediate.key \
    -out client_intermediate.csr -subj "$CLIENT_INTERMEDIATE_SUBJ"
	openssl x509 -req -days $DAYS -in client_intermediate.csr \
    -CA ca_intermediate.crt -CAkey ca_intermediate.key -set_serial 01 \
    -out client_intermediate.crt

	openssl req -new $KEYOPTS -keyout server.key -out server.csr \
    -subj "$SERVER_SUBJ"
	openssl x509 -req -days $DAYS -in server.csr -CA ca.crt -CAkey ca.key \
    -set_serial 03 -out server.crt
) 1>&2


function encode() {
	local label=$1
	local file=$2
	local ind=" "
	
	echo -n "${ind}private static final String $label ="
	(
		read -r line
		ind="$ind    "
		echo -ne "\n$ind\"$line"
		ind="$ind    "
		while read -r line; do
			echo -nE "\n"
			echo -ne "\"\n$ind+ \"$line"
		done
	) <$file
	echo "\";"
	echo
}

encode SAMPLE_CLIENT_CERT client.crt
encode SAMPLE_CLIENT_KEY client.key
encode SAMPLE_CLIENT_INTERMEDIATE_CERT client_intermediate.crt
encode SAMPLE_CLIENT_INTERMEDIATE_KEY client_intermediate.key
encode SAMPLE_SERVER_CERT server.crt
encode SAMPLE_SERVER_KEY server.key
encode SAMPLE_CA_CERT ca.crt
encode SAMPLE_CA_INTERMEDIATE_CERT ca_intermediate.crt
