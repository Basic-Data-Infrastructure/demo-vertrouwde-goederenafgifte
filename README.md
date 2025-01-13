<!--
SPDX-FileCopyrightText: 2024, 2025 Jomco B.V.
SPDX-FileCopyrightText: 2024, 2025 Topsector Logistiek
SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>

SPDX-License-Identifier: AGPL-3.0-or-later
-->

# DIL - Demo Vertrouwde Goederenafgifte

An application to demonstrate the "Vertrouwde Goederenafgifte" use case.

## ⚠ DISCLAIMER ⚠

**The software is for demo purposes only!**  It has not been audited
for security flaws and is not suitable as a starting point to develop
software.  Use at your own risk.

## Lessons learned (sorry Dutch only)

- [Fase 1](doc/bevindingen-fase-1.md)
- [Fase 2](doc/bevindingen-fase-2.md)
- [Fase 3](doc/bevindingen-fase-3.md)

## Environments

This code simulates the following environments:

- [erp](http://localhost:8080/erp/)
- [wms](http://localhost:8080/wms/)
- [tms-1](http://localhost:8080/tms-1/)
- [tms-2](http://localhost:8080/tms-2/)


## Run

Use the following environment variables to configure this demo:

- `PORT`: the port number to listen for HTTP requests, defaults to `8080`
- `STORE_FILE`: the file to store state in, defaults to `/tmp/dil-demo.edn`
- `BASE_URL`: base URL this application is reachable from, defaults to `http://localhost:8080`

Authentication:

- `AUTH_USER_PREFIX`: prefix of user name, defaults to `demo`
- `AUTH_PASS_MULTI`: number to multiply user number with for password, defaults to `31415`
- `AUTH_MAX_ACCOUNTS`: maximum number of user accounts

Datespace:

- `DATASPACE_ID`: The dataspace identifier this demo runs in
- `SATELLITE_ID`: EORI of the iSHARE satellite
- `SATELLITE_ENDPOINT`: URL to the iSHARE satellite

ERP:

- `ERP_EORI`: EORI used by ERP
- `ERP_KEY_FILE`: the file to read the ERP private key from
- `ERP_CHAIN_FILE`: the file to read the ERP certificate chain from
- `ERP_AR_ID`: EORI of the ERP authorization register
- `ERP_AR_ENDPOINT`: URL to the ERP authorization register
- `ERP_PORTBASE_WEBHOOK_SECRET`: Portbase webhook secret part of URL (which is `BASE_URL + "/" + DEMO_NR + "/erp/event/" + ERP_PORTBASE_WEBHOOK_SECRET`)

WMS:

- `WMS_EORI`: EORI used by WMS
- `WMS_KEY_FILE`: the file to read the WMS private key from
- `WMS_CHAIN_FILE`: the file to read the WMS certificate chain from

TMS 1:

- `TMS1_EORI`: EORI
- `TMS1_KEY_FILE`: the file to read the private key from
- `TMS1_CHAIN_FILE`: the file to read the certificate chain from
- `TMS1_AR_ID`: EORI of the authorization register
- `TMS1_AR_ENDPOINT`: URL to the authorization register
- `TMS1_AR_TYPE`: type of authorization register (ishare or poort8)

TMS 2:

- `TMS2_EORI`: EORI
- `TMS2_KEY_FILE`: the file to read the private key from
- `TMS2_CHAIN_FILE`: the file to read the certificate chain from
- `TMS2_AR_ID`: EORI of the authorization register
- `TMS2_AR_ENDPOINT`: URL to the authorization register
- `TMS1_AR_TYPE`: type of authorization register (ishare or poort8)

PMS:

- `PORTBASE_API_BASE_URL`: base URL to portbase event API
- `PORTBASE_API_KEY`: API key for portbase event API

### Register portbase webhook

To register a webhook at portbase to handle incoming events for demo environment 1, run the following:

```sh
clojure -M -m dil-demo.core erp portbase-subscribe 1
```

Copy and store the returned `subscription-id` somewhere to unsubscribe later.

Note: the web server should be accessible from the internet to make
this work.

### Run web server

Run the web server with the following:

```sh
clojure -M -m dil-demo.core
```

Point a web browser to [http://localhost:8080](http://localhost:8080)
and login with user `demo1` with password `31415`.

### Unregister portbase webhook

To unregister a webhook at portbase, run the following (replace
`SUBSCRIPTION-ID` with the ID recorded when registering the webhook:

```sh
clojure -M -m dil-demo.core erp portbase-unsubscribe SUBSCRIPTION-ID
```

Note: there's no need to keep registering and deregistering the webhook when the web server remains accessible at the same location and the webhook secret (`PORTBASE_WEBHOOK_SECRET`) is not changed.

## Deploy

The following creates an uber-jar containing all necessary
dependencies to run the demo environments:

```sh
make
```

This jar-file is runnable with:

```sh
java -jar target/dil-demo.jar
```

## Docker builds

To build and run a docker image locally, with the configuration
environment vars in a `.env` file, and keys and certificates in
`./credentials`, do

```sh
docker build . -t my-image
docker run --env-file=.env --mount="type=bind,source=${PWD}/credentials,destination=/credentials" -p8080:8080 my-image
```

## Fully local demo

See [docker-example](./docker-example) for a docker-compose
configuration that runs the demo locally without dependencies on
external services.

## Architecture documentation

Documentation on the architecture of this demo can be found at
[doc/architecture/architecture-description.md](doc/architecture/architecture-description.md).

## Copying

Copyright (C) 2024, 2025 Jomco B.V.

Copyright (C) 2024, 2025 Topsector Logistiek

[AGPL-3.0-or-later](LICENSES/AGPL-3.0-or-later.txt)
