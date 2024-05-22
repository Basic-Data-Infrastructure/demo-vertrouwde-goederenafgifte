# SPDX-FileCopyrightText: 2024 Jomco B.V.
# SPDX-FileCopyrightText: 2024 Topsector Logistiek
# SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl
# SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
#
# SPDX-License-Identifier: AGPL-3.0-or-later

.PHONY: all lint test check clean test-certs

all: clean check target/dil-demo.jar

%.crt: %.p12
	openssl pkcs12 -in $< -chain -nokeys -legacy -out $@

%.pem: %.p12
	openssl pkcs12 -in $< -nocerts -nodes -legacy -out $@

pems: credentials/EU.EORI.NLPRECIOUSG.pem credentials/EU.EORI.NLSECURESTO.pem credentials/EU.EORI.NLSMARTPHON.pem

certs: credentials/EU.EORI.NLPRECIOUSG.crt credentials/EU.EORI.NLSECURESTO.crt credentials/EU.EORI.NLSMARTPHON.crt

classes/dil_demo/core.class: src/dil_demo/core.clj
	mkdir -p classes
	clojure -M -e "(compile 'dil-demo.core)"

target/dil-demo.jar: classes/dil_demo/core.class
	clojure -M:uberjar --main-class dil-demo.core

resources/test/pem/ca.cert.pem:
	mkdir -p resources/test/pem
	openssl req \
		-x509 -newkey rsa:4096 -sha256 -days 365 -noenc \
		-subj "/CN=TEST-CA" \
		-keyout resources/test/pem/ca.key.pem \
		-out resources/test/pem/ca.cert.pem

resources/test/pem/aa.cert.pem: resources/test/pem/ca.cert.pem
	openssl req \
		-x509 -newkey rsa:4096 -sha256 -days 365 -noenc \
		-subj "/CN=Satellite/serialNumber=EU.EORI.AA" \
		-keyout resources/test/pem/aa.key.pem \
		-out resources/test/pem/aa.cert.pem \
		-CA resources/test/pem/ca.cert.pem \
		-CAkey resources/test/pem/ca.key.pem

resources/test/pem/ar.cert.pem: resources/test/pem/ca.cert.pem
	openssl req \
		-x509 -newkey rsa:4096 -sha256 -days 365 -noenc \
		-subj "/CN=Satellite/serialNumber=EU.EORI.AR" \
		-keyout resources/test/pem/ar.key.pem \
		-out resources/test/pem/ar.cert.pem \
		-CA resources/test/pem/ca.cert.pem \
		-CAkey resources/test/pem/ca.key.pem

resources/test/pem/client.cert.pem: resources/test/pem/ca.cert.pem
	openssl req \
		-x509 -newkey rsa:4096 -sha256 -days 365 -noenc \
		-keyout resources/test/pem/client.key.pem \
		-out resources/test/pem/client.cert.pem \
		-subj "/CN=Satellite/serialNumber=EU.EORI.CLIENT" \
		-CA resources/test/pem/ca.cert.pem \
		-CAkey resources/test/pem/ca.key.pem

test-certs: resources/test/pem/ca.cert.pem resources/test/pem/aa.cert.pem resources/test/pem/ar.cert.pem resources/test/pem/client.cert.pem

lint:
	reuse lint
	clojure -M:lint

test: test-certs
	clojure -M:test

check: lint test

clean:
	rm -rf classes target resources/test/pem
